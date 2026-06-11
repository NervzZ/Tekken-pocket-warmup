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
    @Volatile var headYaw = -20f
    @Volatile var pelvisYaw = -4f
    @Volatile var footAYaw = 12f
    @Volatile var footBYaw = -14f
    @Volatile var footBPitch = -4f
    @Volatile var footBRoll = -4f
    @Volatile var rootDy = 0f
    @Volatile var dumpPose = false
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

        // Battle stance, authored as model-frame offsets ON TOP of the locked
        // T-pose (trim layer semantics): left (B) leg/arm lead, right (A)
        // anchored back; the body yaw lives on the root. rx: + = backward
        // swing / forward torso hunch; arm ry/rz signs mirror per side.
        val STANCE_OFFSETS: Map<String, FloatArray> = mapOf(
            "TORSO" to floatArrayOf(5f, 0f, 0f),
            "HEAD" to floatArrayOf(-4f, 20f, 0f),
            // hips counter-rotate left: less twisted than the shoulders, so
            // the leg base doesn't convert stance depth into foot crossing
            "PELVIS" to floatArrayOf(0f, 12f, 0f),
            // per-leg sagittal sums (thigh+shin+foot rx) must stay 0 so the
            // soles sit flat; lead leg reaches forward, rear stays near-under
            "THIGH_B" to floatArrayOf(-38f, 0f, 26f),
            "SHIN_B" to floatArrayOf(52f, 0f, 0f),
            // FOOT trims are SOLVED, not hand-tuned: each keeps the foot's
            // approved WORLD orientation (lead toe +28 world-yaw open of the
            // body blade, soles flat) under the current leg chain — re-run
            // model/solve_stance_widen.py after ANY thigh/shin change
            // + sole leveled 6deg about the toe axis (was riding its left
            // edge) and heading opened ~7 more deg (model/solve_foot_roll.py)
            "FOOT_B" to floatArrayOf(-3.5f, 27.2f, -10.9f),
            // rear leg solved by position-target IK (model/solve_rear_leg_ik.py):
            // ankle 12cm forward of the v7 plant (18.5cm -> 6.5cm behind the
            // hip), knee pole CONSTRAINED to body-forward (anatomical bend) —
            // hand-nudging these Eulers leaks sideways through the tilted
            // chain, and an unconstrained pole can reverse the knee
            "THIGH_A" to floatArrayOf(-14.0f, 7.1f, -25.7f),
            "SHIN_A" to floatArrayOf(24.5f, -0.6f, 4.3f),
            "FOOT_A" to floatArrayOf(-10.8f, -20.6f, 27.2f),
            "UARM_B" to floatArrayOf(0f, -22f, -92f),
            "FARM_B" to floatArrayOf(0f, -85f, 95f),
            "UARM_A" to floatArrayOf(0f, 25f, 102f),
            "FARM_A" to floatArrayOf(0f, 95f, -100f),
        )
        const val STANCE_BODY_YAW = -22f
        const val ANKLE_REST_Y = 0.083f     // ankle-ball height with sole flat
        // walk anim parameter sets — forward keeps the idle stance posture
        // with distinct lifted steps; backward shifts the upper body back
        // and takes smaller steps (user spec)
        const val WALK_SWING_F = 10f        // thigh swing amplitude, forward
        const val WALK_SWING_B = 7f         // smaller steps backward
        const val WALK_KNEE_F = 22f         // distinct foot lift forward
        const val WALK_KNEE_B = 12f
        const val WALK_LEAN_B = 6f          // torso lean back while retreating
        const val WALK_HIP_BIAS_B = 4f      // hips drawn back vs the feet
        const val RUN_SWING = 42f           // run thigh swing — reaching strides
        const val RUN_LEG_STRAIGHTEN = 0.6f // fraction of stance leg-coil unwound
        const val RUN_KNEE = 42f            // run knee-lift amplitude
        const val RUN_ARM = 26f             // exaggerated shoulder swing
        const val RUN_LEAN = 14f            // forward lean over the stance
        const val RUN_GUARD_UNFOLD = 28f    // forearm unfold: fists drop lower
        const val RUN_GUARD_LOOSEN = 6f     // slight upper-arm relax
        const val JUMP_HEIGHT = 1.45f       // ballistic apex (world units)

        private fun rigPivot(name: String) = MOKUJIN_RIG.first { it.name == name }.pivot

        // model-space landmark points carried by each group — the pose probe
        // reports their posed positions (fists from mokujin_parts.json; the
        // joint landmarks are the child groups' pivots, carried by the parent)
        val PROBE_POINTS = listOf(
            Triple("fist_L", "FARM_B", floatArrayOf(0.5893f, 1.5236f, 0.1477f)),
            Triple("fist_R", "FARM_A", floatArrayOf(-0.5224f, 0.9366f, 0.0712f)),
            Triple("elbow_L", "UARM_B", rigPivot("FARM_B")),
            Triple("elbow_R", "UARM_A", rigPivot("FARM_A")),
            Triple("knee_L", "THIGH_B", rigPivot("SHIN_B")),
            Triple("knee_R", "THIGH_A", rigPivot("SHIN_A")),
            Triple("ankle_L", "SHIN_B", rigPivot("FOOT_B")),
            Triple("ankle_R", "SHIN_A", rigPivot("FOOT_A")),
            Triple("head_top", "HEAD", floatArrayOf(0f, 1.75f, 0f)),
            // toe landmarks 0.3 along each foot's AUTHORED toe axis (from
            // mokujin_parts.json, horizontal — the mesh is authored in a
            // stance so model +z is NOT the toe axis); (toe - ankle) in a
            // dump = the foot's true world heading + sole pitch
            Triple("toe_L", "FOOT_B", rigPivot("FOOT_B").let {
                floatArrayOf(it[0] + 0.1112f, it[1], it[2] + 0.2786f)
            }),
            Triple("toe_R", "FOOT_A", rigPivot("FOOT_A").let {
                floatArrayOf(it[0] + 0.0987f, it[1], it[2] + 0.2833f)
            }),
        )

        // Hand-tuned corrections on top of the analytical T-pose (Euler rx,ry,rz):
        // head has no ball joints to derive direction from; foot centroids are a
        // crude toe-direction proxy. Tuned live via adb, then baked here.
        // head yaw is tuned LIVE by the user (G/H keys) — remote screenshot
        // judgment failed twice on its sign; feet +12 user-accepted.
        val TPOSE_TRIM: Map<String, FloatArray> = mapOf(
            "HEAD" to floatArrayOf(-6f, 0f, 0f),
            "FOOT_A" to floatArrayOf(0f, 12f, 0f),
            "FOOT_B" to floatArrayOf(0f, 12f, 0f),
        )
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
    private val footComp = floatArrayOf(0f, 0f, 0f)
    private var animHopY = 0f
    private val rootM = FloatArray(16)
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
            .intensity(78_000f)
            .direction(-0.4f, -0.85f, -0.45f)
            .castShadows(false)
            .build(engine, sun)
        scene.addEntity(sun)
        scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.65f, 0.68f, 0.74f))
            .intensity(30_000f)
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
                // surface gone (3D disabled / backgrounded): skip the pose
                // math too, the whole layer must cost ~nothing
                if (swapChain == null) return
                asset?.let { a ->
                    applyRigPose(frameTimeNanos)
                    updateRootTransform(a, frameTimeNanos)
                    dumpProbeIfRequested()
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

    // The locked T-pose trims (analytical base corrections + user-tuned values).
    private fun tposeTrims(): HashMap<String, FloatArray> {
        val trim = HashMap(TPOSE_TRIM)
        trim["HEAD"] = floatArrayOf(-6f, Calibration.headYaw, 0f)
        trim["PELVIS"] = floatArrayOf(0f, Calibration.pelvisYaw, 0f)
        trim["FOOT_A"] = floatArrayOf(0f, Calibration.footAYaw, 0f)
        trim["FOOT_B"] = floatArrayOf(
            Calibration.footBPitch, Calibration.footBYaw, Calibration.footBRoll,
        )
        return trim
    }

    private fun sumInto(into: HashMap<String, FloatArray>, add: Map<String, FloatArray>) {
        for ((k, v) in add) {
            val cur = into[k]
            if (cur == null) {
                into[k] = v.copyOf()
            } else {
                into[k] = floatArrayOf(cur[0] + v[0], cur[1] + v[1], cur[2] + v[2])
            }
        }
    }

    // Idle layered on the stance: heavy breathing (slow, torso/arms/head)
    // + knee-flex bob. The body's rise/fall comes from the foot-pinning
    // compensation in applyRigPose — never from a hand-tuned root offset.
    private fun idleOffsets(tSec: Double): Map<String, FloatArray> {
        val bob = (1.0 - kotlin.math.cos(tSec * 2.0 * Math.PI * 0.85)).toFloat() / 2f
        val breath = kotlin.math.sin(tSec * 2.0 * Math.PI * 0.30).toFloat()
        val flex = 6f * bob
        return mapOf(
            "SHIN_B" to floatArrayOf(flex, 0f, 0f),
            "SHIN_A" to floatArrayOf(flex * 1.05f, 0f, 0f),
            "THIGH_B" to floatArrayOf(-flex * 0.45f, 0f, 0f),
            "THIGH_A" to floatArrayOf(-flex * 0.47f, 0f, 0f),
            "FOOT_B" to floatArrayOf(-flex * 0.5f, 0f, 0f),
            "FOOT_A" to floatArrayOf(-flex * 0.55f, 0f, 0f),
            "TORSO" to floatArrayOf(1.8f * breath + flex * 0.25f, 0f, 0f),
            "HEAD" to floatArrayOf(-1.2f * breath, 0f, 0f),
            "UARM_B" to floatArrayOf(0f, 0f, 2.2f * breath),
            "UARM_A" to floatArrayOf(0f, 0f, -2.2f * breath),
            "FARM_B" to floatArrayOf(0f, 0f, 1.5f * breath),
            "FARM_A" to floatArrayOf(0f, 0f, -1.5f * breath),
        )
    }

    // T = symmetric T-pose reference (spinning); V = raw authored pose;
    // default = battle stance = T-pose + STANCE_OFFSETS + idle animation.
    // Base values: size 3 = Euler (rx,ry,rz), size 4 = axis-angle. Trim
    // values: Euler, applied BEFORE the base rotation at the same pivot
    // (model-frame semantics).
    private fun poseLayers(
        tSec: Double,
        includeIdle: Boolean,
    ): Pair<Map<String, FloatArray>, Map<String, FloatArray>> = when {
        // the T-pose is the LOCKED neutral reference — live stance overrides
        // must never reach it (they used to leak in here and corrupt the view)
        Calibration.tPose -> {
            val trim = tposeTrims()
            MOKUJIN_TPOSE to trim
        }
        Calibration.testPose -> emptyMap<String, FloatArray>() to emptyMap()
        else -> {
            val trim = tposeTrims()
            sumInto(trim, STANCE_OFFSETS)
            if (includeIdle) {
                sumInto(trim, idleOffsets(tSec))
                sumInto(trim, walkOffsets())
                sumInto(trim, runOffsets())
                sumInto(trim, backdashOffsets())
                sumInto(trim, sidestepOffsets())
                sumInto(trim, sidewalkOffsets())
                sumInto(trim, jumpOffsets())
                sumInto(trim, crouchdashOffsets())
                sumInto(trim, crouchOffsets())
            }
            sumInto(trim, Calibration.stanceOverrides)
            MOKUJIN_TPOSE to trim
        }
    }

    // walk cycle layered over the stance (sums to zero at walkAmount 0, so
    // it flows out of / back into the idle posture): legs alternate a
    // forward swing with knee-fold clearance, feet counter-pitch to keep
    // the soles from scraping, pelvis sways with the cadence. Phase and
    // blend are driven by ArenaSim (backward walk runs the cycle reversed).
    private fun walkOffsets(): Map<String, FloatArray> {
        // the walk layer cross-fades OUT as the run layer takes over — a
        // sprint is its own stance, not an amplified walk
        val amt = sim.walkAmount * (1f - sim.runAmount)
        if (amt < 0.01f) return emptyMap()
        val p = sim.walkPhase
        // cross-fade the forward/backward parameter sets on the smoothed dir
        val f = kotlin.math.max(0f, sim.walkDir)
        val bk = kotlin.math.max(0f, -sim.walkDir)
        val swingAmp = WALK_SWING_F * f + WALK_SWING_B * bk
        val kneeAmp = WALK_KNEE_F * f + WALK_KNEE_B * bk
        // hips drawn back relative to the feet (thigh bias is cancelled into
        // a real body shift by the ankle centering); torso leans back with a
        // head counter-nod — the retreating upper-body stance shift
        val hipBias = -WALK_HIP_BIAS_B * bk * amt
        val out = HashMap<String, FloatArray>()
        for ((side, off) in listOf("A" to 0f, "B" to Math.PI.toFloat())) {
            val swing = kotlin.math.sin(p + off)
            val clearance = kotlin.math.max(0f, kotlin.math.sin(p + off + 0.45f))
            val thigh = -swingAmp * swing * amt + hipBias
            val shin = kneeAmp * clearance * amt
            out["THIGH_$side"] = floatArrayOf(thigh, 0f, 0f)
            out["SHIN_$side"] = floatArrayOf(shin, 0f, 0f)
            out["FOOT_$side"] = floatArrayOf(-(thigh + shin) * 0.75f, 0f, 0f)
        }
        out["TORSO"] = floatArrayOf(-WALK_LEAN_B * bk * amt, 0f, 0f)
        out["HEAD"] = floatArrayOf(3f * bk * amt, 0f, 0f)
        out["PELVIS"] = floatArrayOf(0f, 2.2f * kotlin.math.sin(p) * amt, 0f)
        return out
    }

    // Run v3 (user spec): STEMS FROM THE BATTLE STANCE — no stance unwind.
    // Much bigger strides than the walk, a bit of forward lean, the guard
    // loosened LOWER (forearms unfold so the fists drop), and both
    // shoulders pumping up/down with the running cadence.
    private fun runOffsets(): Map<String, FloatArray> {
        val r = sim.runAmount
        if (r < 0.01f) return emptyMap()
        val p = sim.walkPhase
        val out = HashMap<String, FloatArray>()
        fun add(g: String, rx: Float, ry: Float, rz: Float) {
            val cur = out.getOrPut(g) { floatArrayOf(0f, 0f, 0f) }
            cur[0] += rx * r; cur[1] += ry * r; cur[2] += rz * r
        }
        // legs run STRAIGHTER than the coiled stance: unwind a fraction of
        // the stance's LEG offsets only (guard/torso/blade stay untouched)
        for (g in arrayOf("THIGH_A", "SHIN_A", "FOOT_A", "THIGH_B", "SHIN_B", "FOOT_B")) {
            val v = STANCE_OFFSETS[g] ?: continue
            add(
                g,
                -v[0] * RUN_LEG_STRAIGHTEN,
                -v[1] * RUN_LEG_STRAIGHTEN,
                -v[2] * RUN_LEG_STRAIGHTEN,
            )
        }
        for ((side, off) in listOf("A" to 0f, "B" to Math.PI.toFloat())) {
            val swing = kotlin.math.sin(p + off)
            val clearance = kotlin.math.max(0f, kotlin.math.sin(p + off + 0.45f))
            val thigh = -RUN_SWING * swing
            val shin = RUN_KNEE * clearance
            add("THIGH_$side", thigh, 0f, 0f)
            add("SHIN_$side", shin, 0f, 0f)
            add("FOOT_$side", -(thigh + shin) * 0.7f, 0f, 0f)
        }
        // shoulders pump opposite the same-side leg; guard drops lower
        val pump = RUN_ARM * kotlin.math.sin(p)
        add("UARM_B", -pump, 0f, RUN_GUARD_LOOSEN)
        add("FARM_B", 0f, 0f, -RUN_GUARD_UNFOLD)
        add("UARM_A", pump, 0f, -RUN_GUARD_LOOSEN)
        add("FARM_A", 0f, 0f, RUN_GUARD_UNFOLD)
        add("TORSO", RUN_LEAN, 0f, 0f)
        add("HEAD", -4f, 0f, 0f)
        add("PELVIS", 0f, 3f * kotlin.math.sin(p), 0f)
        return out
    }

    // half-sine envelope over the window [a, b] of normalized progress u
    private fun bump(u: Float, a: Float, b: Float): Float =
        if (u <= a || u >= b) 0f
        else kotlin.math.sin(Math.PI.toFloat() * (u - a) / (b - a))

    // Backdash (34f) as a BIG STEP BACK, not a hop (user spec): quick brace
    // onto the lead/left (B) foot, the right (A) foot steps back through the
    // air and GROUNDS (~frame 14) while the body is pushed backwards by the
    // left leg; only THEN does the left foot lift and step back home into
    // the stance. Feet alternate — never both airborne, no root hop.
    private fun backdashOffsets(): Map<String, FloatArray> {
        val u = sim.bdProgress
        if (u < 0f) return emptyMap()
        // no brace phase: the stance is already poised on the lead foot, so
        // the push fires on frame 0 and the left foot is off near-instantly;
        // all foot action lives inside the f0-19 movement window, the
        // recovery frames are posture settle only (user spec)
        // all offsets reach zero by ~u0.54: once the step is done the pose
        // IS the breathing idle for the rest of the recovery — no settle
        // layer (a late torso forward-tilt read as a janky spring-back bob)
        val push = bump(u, 0.00f, 0.30f)    // lead-leg drive: knee EXTENDS
        val liftA = bump(u, 0.02f, 0.28f)   // right foot steps back, grounded ~f10
        val liftB = bump(u, 0.26f, 0.56f)   // then the lead folds, lifts, plants
        val leanBd = bump(u, 0.00f, 0.52f)  // lean back held through the travel

        val thighA = 6f * liftA
        val shinA = 22f * liftA
        // the push nearly fully unflexes the lead knee (stance 52 - 42 trim)
        // while the thigh trails back — a hard straight-leg drive; then the
        // knee re-flexes to lift the foot home
        val thighB = 12f * push - 8f * liftB
        val shinB = -42f * push + 30f * liftB
        return mapOf(
            "THIGH_A" to floatArrayOf(thighA, 0f, 0f),
            "SHIN_A" to floatArrayOf(shinA, 0f, 0f),
            "FOOT_A" to floatArrayOf(-(thighA + shinA) * 0.6f, 0f, 0f),
            "THIGH_B" to floatArrayOf(thighB, 0f, 0f),
            "SHIN_B" to floatArrayOf(shinB, 0f, 0f),
            // 0.5 comp leaves a heel-up residual on the pushing foot
            "FOOT_B" to floatArrayOf(-(thighB + shinB) * 0.5f, 0f, 0f),
            "TORSO" to floatArrayOf(-9f * leanBd, 0f, 0f),
            "HEAD" to floatArrayOf(4f * leanBd, 0f, 0f),
            "PELVIS" to floatArrayOf(0f, -3f * push, 0f),
        )
    }

    // builds the hierarchical group-world chains for a pose into `out`
    private fun computeChains(
        angles: Map<String, FloatArray>,
        trims: Map<String, FloatArray>,
        tourSel: Int,
        out: HashMap<String, FloatArray>,
    ) {
        out.clear()
        for ((index, rt) in rig.withIndex()) {
            val g = rt.group
            val local = FloatArray(16)
            Matrix.setIdentityM(local, 0)
            Matrix.translateM(local, 0, g.pivot[0], g.pivot[1], g.pivot[2])
            // trim FIRST so its axes mean model axes regardless of the base
            val t = trims[g.name]
            if (t != null && t.size >= 3) {
                if (t[1] != 0f) Matrix.rotateM(local, 0, t[1], 0f, 1f, 0f)
                if (t[0] != 0f) Matrix.rotateM(local, 0, t[0], 1f, 0f, 0f)
                if (t[2] != 0f) Matrix.rotateM(local, 0, t[2], 0f, 0f, 1f)
            }
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
                val pw = out[g.parent]
                if (pw != null) {
                    FloatArray(16).also { Matrix.multiplyMM(it, 0, pw, 0, local, 0) }
                } else local
            } else {
                local
            }
            out[g.name] = world
        }
    }

    private fun transformPoint(w: FloatArray, p: FloatArray, out: FloatArray) {
        out[0] = w[0] * p[0] + w[4] * p[1] + w[8] * p[2] + w[12]
        out[1] = w[1] * p[0] + w[5] * p[1] + w[9] * p[2] + w[13]
        out[2] = w[2] * p[0] + w[6] * p[1] + w[10] * p[2] + w[14]
    }

    private fun applyRigPose(frameTimeNanos: Long) {
        val tm = engine.transformManager
        val tSec = frameTimeNanos / 1_000_000_000.0
        val (angles, trims) = poseLayers(tSec, includeIdle = true)

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
            // paused doubles as the T-pose camera lock — only the tour resets it
            if (!Calibration.tPose) Calibration.paused = false
            if (Calibration.tPose) {
                Calibration.display.value =
                    "head %.0f  pelvis %.0f  fA %.0f  fB %.0f  fB-pitch %.0f (D/F)  fB-roll %.0f (A/S)".format(
                        Calibration.headYaw, Calibration.pelvisYaw,
                        Calibration.footAYaw, Calibration.footBYaw,
                        Calibration.footBPitch, Calibration.footBRoll,
                    )
            } else if (Calibration.display.value.isNotEmpty()) {
                Calibration.display.value = ""
            }
            -1
        }

        computeChains(angles, trims, tourSel, groupWorld)

        // foot seat + center, all ABSOLUTE: the ankle midpoint is cancelled
        // at the root every frame — vertically onto the ankle-ball rest
        // height (self-seating) and horizontally onto the char-space origin
        // (self-centering: the ground dot IS the stance center by
        // definition). Subsumes the old relative idle pin, and needs no
        // second no-idle pose evaluation
        val inStance = !Calibration.tPose && !Calibration.testPose
        if (inStance) {
            val cur = FloatArray(3)
            footComp[0] = 0f; footComp[1] = 0f; footComp[2] = 0f
            // vertical ground contact = the LOWER ankle (the planted foot) —
            // averaging would sink the support foot whenever a walk/step
            // anim lifts the other one
            var loY = Float.MAX_VALUE
            for (side in arrayOf("A", "B")) {
                val ankle = rigPivot("FOOT_$side")
                transformPoint(groupWorld["SHIN_$side"]!!, ankle, cur)
                footComp[0] += cur[0] / 2f
                if (cur[1] < loY) loY = cur[1]
                footComp[2] += cur[2] / 2f
            }
            footComp[1] = loY - ANKLE_REST_Y
        } else {
            footComp[0] = 0f; footComp[1] = 0f; footComp[2] = 0f
        }

        for (rt in rig) {
            val world = groupWorld[rt.group.name] ?: continue
            for (i in rt.entities.indices) {
                Matrix.multiplyMM(scratch, 0, world, 0, rt.pL0[i], 0)
                Matrix.multiplyMM(m, 0, rt.pInv[i], 0, scratch, 0)
                tm.setTransform(tm.getInstance(rt.entities[i]), m)
            }
        }
    }

    fun dumpProbeIfRequested() {
        if (!Calibration.dumpPose) return
        Calibration.dumpPose = false
        val mp = FloatArray(3)
        val sb = StringBuilder("pose probe (WORLD space):\n")
        for ((label, grp, p) in PROBE_POINTS) {
            val w = groupWorld[grp] ?: continue
            transformPoint(w, p, mp)
            val x = rootM[0] * mp[0] + rootM[4] * mp[1] + rootM[8] * mp[2] + rootM[12]
            val y = rootM[1] * mp[0] + rootM[5] * mp[1] + rootM[9] * mp[2] + rootM[13]
            val z = rootM[2] * mp[0] + rootM[6] * mp[1] + rootM[10] * mp[2] + rootM[14]
            sb.append("  %-9s (%6.3f, %6.3f, %6.3f)\n".format(label, x, y, z))
        }
        sb.append(
            "  sim: state=%s ssDir=%.0f facing=%.0f ssU=%.2f bdU=%.2f run=%.2f\n".format(
                sim.state, sim.ssDir, sim.facingF, sim.ssProgress, sim.bdProgress,
                sim.runAmount,
            ),
        )
        android.util.Log.i("PoseProbe", sb.toString())
    }

    // Sidestep (24f, user spec): the OUTSIDE leg (opposite the step side)
    // pushes the body across — knees stay near-straight (no flexing). The
    // upper body leans/shifts toward the destination EARLY (the symmetric
    // thigh bias becomes a real lateral body shift via the ankle centering),
    // and the feet re-gather under the shifted body late. Lateral travel is
    // the orbit arc. Input up/down maps to a model side via facing.
    private fun sidestepOffsets(): Map<String, FloatArray> {
        val u = sim.ssProgress
        if (u < 0f) return emptyMap()
        val dirX = sim.ssDir * sim.facingF      // model +x = char's left
        val lean = bump(u, 0.00f, 0.55f)        // upper body leads
        // INSIDE leg (destination side): the knee comes UP on frame one —
        // thigh raised hard, shin folded ~parallel to the ground — turns
        // outward in the air, then plants by mid-step (user choreography)
        val inLift = bump(u, 0.00f, 0.52f)
        val inTurn = bump(u, 0.10f, 0.52f)
        // OUTSIDE leg: pushes while GROUNDED early, then takes its own step
        // in once the inside foot is planted
        val push = bump(u, 0.05f, 0.48f)
        val outStep = bump(u, 0.42f, 0.90f)
        // sides calibrated by probe + user-confirmed: ssup = step into the
        // background = B-side destination for P1
        val inSide = if (dirX > 0f) "B" else "A"
        val outSide = if (dirX > 0f) "A" else "B"
        val bias = -5f * dirX * lean
        val pushZ = -10f * dirX * push          // outside leg angles out = drive
        val out = HashMap<String, FloatArray>()
        // high-knee needs headroom: partially unwind this leg's stance coil
        // while it's airborne (the lead leg is already deeply bent).
        // NOTE: trim degrees compress ~0.65:1 into world angles through the
        // conjugated chain — amplitudes below are sized for that (probe-fit)
        val unwind = 0.5f * inLift
        for (g in arrayOf("THIGH_$inSide", "SHIN_$inSide", "FOOT_$inSide")) {
            val v = STANCE_OFFSETS[g] ?: continue
            out[g] = floatArrayOf(-v[0] * unwind, -v[1] * unwind, -v[2] * unwind)
        }
        fun add(g: String, rx: Float, ry: Float, rz: Float) {
            val cur = out.getOrPut(g) { floatArrayOf(0f, 0f, 0f) }
            cur[0] += rx; cur[1] += ry; cur[2] += rz
        }
        add("THIGH_$inSide", -52f * inLift, 0f, bias + 8f * dirX * inTurn)
        add("SHIN_$inSide", 80f * inLift, 0f, 0f)
        add("FOOT_$inSide", -20f * inLift, 0f, -bias * 0.8f - 6f * dirX * inTurn)
        add("THIGH_$outSide", -6f * outStep, 0f, bias + pushZ)
        add("SHIN_$outSide", -4f * push + 30f * outStep, 0f, 0f)
        add(
            "FOOT_$outSide",
            (4f * push - 22f * outStep) * 0.6f, 0f, -(bias + pushZ) * 0.8f,
        )
        out["TORSO"] = floatArrayOf(0f, 3f * dirX * lean, -5f * dirX * lean)
        out["HEAD"] = floatArrayOf(0f, -3f * dirX * lean, 2.5f * dirX * lean)
        out["PELVIS"] = floatArrayOf(0f, -3f * dirX * lean, 0f)
        return out
    }

    // Jump (uncancelable, lands into crouch): ballistic root arc through the
    // animHopY channel (added AFTER the foot seat so the body truly leaves
    // the ground), legs tuck mid-flight, and ub/uf arch the torso slightly
    // backward/forward with the drift. The landing compression is the crouch
    // state blending in on touchdown.
    private fun jumpOffsets(): Map<String, FloatArray> {
        val u = sim.jumpProgress
        if (u < 0f) {
            animHopY = 0f
            return emptyMap()
        }
        // phase 1 (first ~6f): grounded knee-compression dip — the explosive
        // pre-load. Phase 2: fast ballistic to a high apex and back down.
        val dip = bump(u, 0.00f, 0.20f)
        val v = ((u - 0.20f) / 0.80f).coerceIn(0f, 1f)
        animHopY = JUMP_HEIGHT * 4f * v * (1f - v)
        val tuck = bump(u, 0.28f, 0.86f)
        val arch = bump(u, 0.24f, 0.90f) * sim.jumpDirF
        val thigh = -26f * dip - 34f * tuck
        val shin = 46f * dip + 56f * tuck
        return mapOf(
            "THIGH_A" to floatArrayOf(thigh, 0f, 0f),
            "SHIN_A" to floatArrayOf(shin, 0f, 0f),
            "FOOT_A" to floatArrayOf(-(thigh + shin) * 0.6f, 0f, 0f),
            "THIGH_B" to floatArrayOf(thigh, 0f, 0f),
            "SHIN_B" to floatArrayOf(shin, 0f, 0f),
            "FOOT_B" to floatArrayOf(-(thigh + shin) * 0.6f, 0f, 0f),
            "TORSO" to floatArrayOf(6f * dip + 16f * arch, 0f, 0f),
            "HEAD" to floatArrayOf(-2f * dip - 7f * arch, 0f, 0f),
        )
    }

    // Crouchdash (f,n,d,df): a forward slide sinking into the full crouch.
    // The fold ramps to EXACTLY the crouch pose by u0.8 (the sim seeds
    // crouchAmount on exit, so both the run-out and the b/f cancels hand
    // over at matching depth); an early lunge leans the body into the slide.
    private fun crouchdashOffsets(): Map<String, FloatArray> {
        val u = sim.cdProgress
        if (u < 0f) return emptyMap()
        val ramp = (u / 0.8f).coerceAtMost(1f)
        val lunge = bump(u, 0.00f, 0.55f)
        val thigh = -32f * ramp - 8f * lunge
        val shin = 58f * ramp
        return mapOf(
            "THIGH_A" to floatArrayOf(-32f * ramp, 0f, 0f),
            "SHIN_A" to floatArrayOf(shin, 0f, 0f),
            "FOOT_A" to floatArrayOf(-(-32f * ramp + shin), 0f, 0f),
            "THIGH_B" to floatArrayOf(thigh, 0f, 0f),
            "SHIN_B" to floatArrayOf(shin, 0f, 0f),
            "FOOT_B" to floatArrayOf(-(thigh + shin), 0f, 0f),
            "TORSO" to floatArrayOf(10f * ramp + 8f * lunge, 0f, 0f),
            "HEAD" to floatArrayOf(-4f * ramp - 3f * lunge, 0f, 0f),
        )
    }

    // Sidewalk: continuous strafe while the sidestep's direction stays held
    // (u,U / d,D). Alternating small lateral steps — each leg lifts in its
    // half-cycle with a slight toward-the-direction reach — under a held
    // lean into the travel. Sums to zero at rest like every motion layer.
    private fun sidewalkOffsets(): Map<String, FloatArray> {
        val amt = sim.swAmount
        if (amt < 0.01f) return emptyMap()
        val p = sim.swPhase
        val dirX = sim.swDir * sim.facingF
        val out = HashMap<String, FloatArray>()
        for ((side, off) in listOf("A" to 0f, "B" to Math.PI.toFloat())) {
            val lift = kotlin.math.max(0f, kotlin.math.sin(p + off))
            val thigh = -16f * lift * amt
            val shin = 26f * lift * amt
            out["THIGH_$side"] = floatArrayOf(thigh, 0f, 5f * dirX * lift * amt)
            out["SHIN_$side"] = floatArrayOf(shin, 0f, 0f)
            out["FOOT_$side"] = floatArrayOf(
                -(thigh + shin) * 0.6f, 0f, -4f * dirX * lift * amt,
            )
        }
        out["TORSO"] = floatArrayOf(0f, 2f * dirX * amt, -3.5f * dirX * amt)
        out["HEAD"] = floatArrayOf(0f, -2f * dirX * amt, 1.8f * dirX * amt)
        out["PELVIS"] = floatArrayOf(0f, 2f * kotlin.math.sin(p) * amt, 0f)
        return out
    }

    // Crouch: a fast duck (~3-4 frames via the sim's blend) — deep double
    // knee fold with the soles kept flat, torso folding slightly over the
    // knees; the ankle seat self-grounds the lowered hips. Free state: the
    // amount just follows the sim blend in and out.
    private fun crouchOffsets(): Map<String, FloatArray> {
        val a = sim.crouchAmount
        if (a < 0.01f) return emptyMap()
        val thigh = -32f * a
        val shin = 58f * a
        return mapOf(
            "THIGH_A" to floatArrayOf(thigh, 0f, 0f),
            "SHIN_A" to floatArrayOf(shin, 0f, 0f),
            "FOOT_A" to floatArrayOf(-(thigh + shin), 0f, 0f),
            "THIGH_B" to floatArrayOf(thigh, 0f, 0f),
            "SHIN_B" to floatArrayOf(shin, 0f, 0f),
            "FOOT_B" to floatArrayOf(-(thigh + shin), 0f, 0f),
            "TORSO" to floatArrayOf(10f * a, 0f, 0f),
            "HEAD" to floatArrayOf(-4f * a, 0f, 0f),
        )
    }

    private fun updateRootTransform(a: FilamentAsset, frameTimeNanos: Long) {
        val yaw = when {
            Calibration.tPose && Calibration.paused -> 0f   // locked facing the camera
            Calibration.auto || Calibration.tPose ->
                (frameTimeNanos / 1_000_000_000.0 * 30.0).toFloat() % 360f
            // base yaw flips with the side; the stance blade rides ON TOP so
            // P1->P2 is an exact 180. Multiplying the sum by facing flipped
            // the blade too: P2 ended up -68 vs +68 = 136 deg (user-caught)
            else -> BASE_YAW_DEG * sim.facingF + STANCE_BODY_YAW
        }
        val inStance = !Calibration.testPose && !Calibration.tPose
        // animHopY rides on top of the foot seat so hops can leave the ground
        val dy = if (inStance) Calibration.rootDy + animHopY else 0f
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, 0f, dy, 0f)
        Matrix.rotateM(m, 0, yaw, 0f, 1f, 0f)
        if (inStance) {
            // absolute foot seat/center (model-frame, render magnitude);
            // the old STANCE_ROOT_DZ hips-back shift is obsolete — any root
            // z offset would just be cancelled by the centering
            Matrix.translateM(
                m, 0,
                -footComp[0] * modelScale,
                -footComp[1] * modelScale,
                -footComp[2] * modelScale,
            )
        }
        Matrix.scaleM(m, 0, modelScale, modelScale, modelScale)
        // bbox auto-centering only outside stance (tour/T-pose spins) — in
        // stance the absolute ankle centering owns x/z, and leaving these in
        // moved the stance ~6cm off the ground dot
        Matrix.translateM(
            m, 0,
            if (inStance) 0f else modelOffX / modelScale,
            modelOffY / modelScale,
            if (inStance) 0f else modelOffZ / modelScale,
        )
        System.arraycopy(m, 0, rootM, 0, 16)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(a.root), m)
    }
}
