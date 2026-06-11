package com.nervz.movementtrainer.gfx

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.Matrix
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Rig calibration/verification: the tour cycles limb GROUPS (C key), pausing
// with X; V toggles a static test pose that exercises the joint pivots.
object Calibration {
    @Volatile var part = -1
    @Volatile var auto = false
    @Volatile var paused = false
    @Volatile var testPose = false
    @Volatile var tPose = false
    @Volatile var rootDy = 0f
    val stanceOverrides = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    @Volatile var autoStartNanos = 0L
    @Volatile var pauseNanos = 0L
    val display = androidx.compose.runtime.mutableStateOf("")
}

// Mokujin layer: translucent Filament surface over the GL ground layer.
// The model is an unrigged collection of 116 rigid parts; MOKUJIN_RIG groups
// them into limbs with ball-joint pivots, and this view poses the groups
// hierarchically (parent rotations carry children).
class MokujinView(context: Context, private val sim: ArenaSim) : SurfaceView(context) {

    companion object {
        init {
            Filament.init()
            Gltfio.init()
        }
        private const val TARGET_HEIGHT = 1.66f
        private const val BASE_YAW_DEG = 90f

        // Battle-stance offsets from the authored pose: per group (rx, ry, rz)
        // degrees about the model-space pivot. rx: + swings backward, - forward.
        // Tuned live via adb (Calibration.stanceOverrides), then baked here.
        val STANCE: Map<String, FloatArray> = mapOf(
            "TORSO" to floatArrayOf(-6f, 12f, 0f),
            "HEAD" to floatArrayOf(4f, -8f, 0f),
            "UARM_A" to floatArrayOf(-12f, 0f, 0f),
            "FARM_A" to floatArrayOf(-38f, 0f, 0f),
            "UARM_B" to floatArrayOf(6f, 0f, 0f),
            "FARM_B" to floatArrayOf(8f, 0f, 0f),
            "THIGH_A" to floatArrayOf(-14f, 0f, 0f),
            "SHIN_A" to floatArrayOf(20f, 0f, 0f),
            "FOOT_A" to floatArrayOf(-6f, 0f, 0f),
            "THIGH_B" to floatArrayOf(10f, 0f, 0f),
            "SHIN_B" to floatArrayOf(24f, 0f, 0f),
            "FOOT_B" to floatArrayOf(-18f, 0f, 0f),
        )
        const val STANCE_ROOT_DY = -0.07f
    }

    private val engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val filaView: View = engine.createView()
    private val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    private var modelScale = 1f
    private var modelOffX = 0f
    private var modelOffY = 0f
    private var modelOffZ = 0f

    // Per entity, precomputed so a model-space group transform W applies as
    // local' = pInv * W * pL0 regardless of the node's parent chain:
    //   P = rootWorld^-1 * nodeWorld * L0^-1   (parent chain in model space)
    private class GroupInstance(
        val group: RigGroup,
        val entities: IntArray,
        val pInv: Array<FloatArray>,
        val pL0: Array<FloatArray>,
    )

    private var rig: List<GroupInstance> = emptyList()
    private val groupWorld = HashMap<String, FloatArray>()
    private val m = FloatArray(16)
    private val scratch = FloatArray(16)

    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)

        filaView.scene = scene
        filaView.camera = camera
        filaView.blendMode = View.BlendMode.TRANSLUCENT
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }

        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.96f, 0.9f)
            .intensity(60_000f)
            .direction(-0.4f, -0.85f, -0.45f)
            .castShadows(false)
            .build(engine, sun)
        scene.addEntity(sun)
        scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.65f, 0.68f, 0.74f))
            .intensity(22_000f)
            .build(engine)

        loadModel(context)

        uiHelper.isOpaque = false
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            }

            override fun onDetachedFromSurface() {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                    swapChain = null
                }
            }

            override fun onResized(width: Int, height: Int) {
                camera.setProjection(
                    42.0, width.toDouble() / height.toDouble(), 0.4, 80.0,
                    Camera.Fov.VERTICAL,
                )
                filaView.viewport = Viewport(0, 0, width, height)
            }
        }
        uiHelper.attachTo(this)

        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                Choreographer.getInstance().postFrameCallback(this)
                asset?.let { a ->
                    applyRigPose(frameTimeNanos)
                    updateRootTransform(a, frameTimeNanos)
                }
                camera.lookAt(
                    sim.camEyeX.toDouble(), sim.camEyeY.toDouble(), sim.camEyeZ.toDouble(),
                    sim.camCtrX.toDouble(), 1.0, 0.0,
                    0.0, 1.0, 0.0,
                )
                if (uiHelper.isReadyToRender) {
                    swapChain?.let { sc ->
                        if (renderer.beginFrame(sc, frameTimeNanos)) {
                            renderer.render(filaView)
                            renderer.endFrame()
                        }
                    }
                }
            }
        })
    }

    private fun loadModel(context: Context) {
        val bytes = context.assets.open("mokujin.glb").use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .put(bytes)
        buffer.rewind()
        val loader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
        val a = loader.createAsset(buffer) ?: return
        val rl = ResourceLoader(engine)
        rl.loadResources(a)
        rl.destroy()
        a.releaseSourceData()
        scene.addEntities(a.entities)

        val bb = a.boundingBox
        val height = bb.halfExtent[1] * 2f
        modelScale = TARGET_HEIGHT / height
        modelOffX = -bb.center[0] * modelScale
        modelOffY = -(bb.center[1] - bb.halfExtent[1]) * modelScale
        modelOffZ = -bb.center[2] * modelScale

        val tm = engine.transformManager
        val rootWorld = FloatArray(16)
        tm.getWorldTransform(tm.getInstance(a.root), rootWorld)
        val rootWorldInv = FloatArray(16)
        Matrix.invertM(rootWorldInv, 0, rootWorld, 0)

        rig = MOKUJIN_RIG.map { g ->
            val ents = g.nodes.map { a.getFirstEntityByName(it) }.filter { it != 0 }.toIntArray()
            val pInvs = ArrayList<FloatArray>(ents.size)
            val pL0s = ArrayList<FloatArray>(ents.size)
            for (e in ents) {
                val inst = tm.getInstance(e)
                val l0 = FloatArray(16)
                tm.getTransform(inst, l0)
                val w = FloatArray(16)
                tm.getWorldTransform(inst, w)
                val wModel = FloatArray(16)
                Matrix.multiplyMM(wModel, 0, rootWorldInv, 0, w, 0)
                val l0Inv = FloatArray(16)
                Matrix.invertM(l0Inv, 0, l0, 0)
                val p = FloatArray(16)
                Matrix.multiplyMM(p, 0, wModel, 0, l0Inv, 0)
                val pInv = FloatArray(16)
                Matrix.invertM(pInv, 0, p, 0)
                val pl0 = FloatArray(16)
                Matrix.multiplyMM(pl0, 0, p, 0, l0, 0)
                pInvs.add(pInv)
                pL0s.add(pl0)
            }
            GroupInstance(g, ents, pInvs.toTypedArray(), pL0s.toTypedArray())
        }
        asset = a
    }

    // T = symmetric T-pose reference (spinning); V = raw authored pose;
    // default = battle stance. Map values: size 3 = Euler (rx,ry,rz),
    // size 4 = axis-angle (deg, x, y, z).
    private fun poseAngles(): Map<String, FloatArray> = when {
        Calibration.tPose -> MOKUJIN_TPOSE
        Calibration.testPose -> emptyMap()
        else -> {
            val merged = HashMap(STANCE)
            merged.putAll(Calibration.stanceOverrides)
            merged
        }
    }

    private fun applyRigPose(frameTimeNanos: Long) {
        val tm = engine.transformManager
        val angles = poseAngles()

        val tourSel = if (Calibration.auto) {
            if (Calibration.autoStartNanos == 0L) Calibration.autoStartNanos = frameTimeNanos
            if (Calibration.paused) {
                if (Calibration.pauseNanos == 0L) Calibration.pauseNanos = frameTimeNanos
            } else if (Calibration.pauseNanos != 0L) {
                Calibration.autoStartNanos += frameTimeNanos - Calibration.pauseNanos
                Calibration.pauseNanos = 0L
            }
            val effectiveNow = if (Calibration.paused) Calibration.pauseNanos else frameTimeNanos
            val elapsed = (effectiveNow - Calibration.autoStartNanos) / 1_000_000_000.0
            val idx = ((elapsed / 2.5) % rig.size).toInt()
            val pauseTag = if (Calibration.paused) "  [PAUSED — X resumes]" else ""
            Calibration.display.value = "group $idx — ${rig[idx].group.name}$pauseTag"
            idx
        } else {
            Calibration.autoStartNanos = 0L
            Calibration.pauseNanos = 0L
            Calibration.paused = false
            if (Calibration.display.value.isNotEmpty()) Calibration.display.value = ""
            -1
        }

        groupWorld.clear()
        for ((index, rt) in rig.withIndex()) {
            val g = rt.group
            val local = FloatArray(16)
            Matrix.setIdentityM(local, 0)
            Matrix.translateM(local, 0, g.pivot[0], g.pivot[1], g.pivot[2])
            val a = angles[g.name]
            if (a != null) {
                if (a.size == 4) {
                    if (a[0] != 0f) Matrix.rotateM(local, 0, a[0], a[1], a[2], a[3])
                } else {
                    if (a[1] != 0f) Matrix.rotateM(local, 0, a[1], 0f, 1f, 0f)
                    if (a[0] != 0f) Matrix.rotateM(local, 0, a[0], 1f, 0f, 0f)
                    if (a[2] != 0f) Matrix.rotateM(local, 0, a[2], 0f, 0f, 1f)
                }
            }
            if (index == tourSel) Matrix.scaleM(local, 0, 1.45f, 1.45f, 1.45f)
            Matrix.translateM(local, 0, -g.pivot[0], -g.pivot[1], -g.pivot[2])
            val world = if (g.parent != null) {
                val pw = groupWorld[g.parent]
                if (pw != null) {
                    FloatArray(16).also { Matrix.multiplyMM(it, 0, pw, 0, local, 0) }
                } else local
            } else {
                local
            }
            groupWorld[g.name] = world
            for (i in rt.entities.indices) {
                Matrix.multiplyMM(scratch, 0, world, 0, rt.pL0[i], 0)
                Matrix.multiplyMM(m, 0, rt.pInv[i], 0, scratch, 0)
                tm.setTransform(tm.getInstance(rt.entities[i]), m)
            }
        }
    }

    private fun updateRootTransform(a: FilamentAsset, frameTimeNanos: Long) {
        val yaw = if (Calibration.auto || Calibration.tPose) {
            (frameTimeNanos / 1_000_000_000.0 * 30.0).toFloat() % 360f
        } else {
            BASE_YAW_DEG * sim.facingF
        }
        val dy = if (Calibration.testPose || Calibration.tPose) {
            0f
        } else {
            STANCE_ROOT_DY + Calibration.rootDy
        }
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, 0f, dy, 0f)
        Matrix.rotateM(m, 0, yaw, 0f, 1f, 0f)
        Matrix.scaleM(m, 0, modelScale, modelScale, modelScale)
        Matrix.translateM(m, 0, modelOffX / modelScale, modelOffY / modelScale, modelOffZ / modelScale)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(a.root), m)
    }
}
