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

// Skinned mokujin layer: a translucent Filament surface stacked over the GL
// ground layer. Reads camera + root motion from the shared ArenaSim and loops
// the model's embedded idle clip.
class MokujinView(context: Context, private val sim: ArenaSim) : SurfaceView(context) {

    companion object {
        init {
            Filament.init()
            Gltfio.init()
        }
        // model height in arena units and yaw correction for the model's
        // authored forward direction (tuned empirically)
        private const val TARGET_HEIGHT = 1.66f
        private const val BASE_YAW_DEG = 90f
    }

    private val engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val filaView: View = engine.createView()
    private val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private var asset: FilamentAsset? = null
    private var animDur = 1f
    private var modelScale = 1f
    private var modelOffX = 0f
    private var modelOffY = 0f
    private var modelOffZ = 0f

    private val m = FloatArray(16)

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
                    val animator = a.instance.animator
                    if (animator.animationCount > 0) {
                        val t = ((frameTimeNanos / 1_000_000_000.0) % animDur).toFloat()
                        animator.applyAnimation(0, t)
                        animator.updateBoneMatrices()
                    }
                    updateRootTransform(a)
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
        if (a.instance.animator.animationCount > 0) {
            animDur = a.instance.animator.getAnimationDuration(0)
        }

        val bb = a.boundingBox
        val height = bb.halfExtent[1] * 2f
        modelScale = TARGET_HEIGHT / height
        modelOffX = -bb.center[0] * modelScale
        modelOffY = -(bb.center[1] - bb.halfExtent[1]) * modelScale
        modelOffZ = -bb.center[2] * modelScale
        asset = a
    }

    private fun updateRootTransform(a: FilamentAsset) {
        val f = sim.facingF
        // T(root motion) * Rz(world-frame lean) * Ry(yaw) * S * T(center fix)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, 0f, sim.charHopY - 0.30f * sim.charCrouch, 0f)
        Matrix.rotateM(m, 0, -sim.charLean * f, 0f, 0f, 1f)
        Matrix.rotateM(m, 0, BASE_YAW_DEG * f + sim.charTwist * f, 0f, 1f, 0f)
        Matrix.scaleM(m, 0, modelScale, modelScale, modelScale)
        Matrix.translateM(m, 0, modelOffX / modelScale, modelOffY / modelScale, modelOffZ / modelScale)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(a.root), m)
    }
}
