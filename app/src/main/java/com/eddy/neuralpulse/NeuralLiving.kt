package com.eddy.neuralpulse

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「Loyea Neural Living」神经元核心场景模型。
 *
 * 移植自 Loyea（同作者的私有项目）陪伴模式的 Neural Living 实时渲染组件，
 * 视觉原型为配套演示页内嵌 canvas 的逐行移植：
 * 同一 LCG 随机种子(91237)、同一拓扑(135 核心节点 + 610 外壳节点 + 度上限 5 的近邻边、
 * 28 条三锚点桥接路径、2 组直立轨道弧线各 3 段、30 尘埃、36 电火花)、同一五层呼吸帧与漂移场。
 *
 * NeuralPulse 扩展：新增音频驱动钩子 setAudioDrive(level, beat) ——
 * 响度抬升活跃度与呼吸/漂移振幅，节拍冲击加速时间流并放大电火花游速；
 * 两值均为 0 时逐帧行为与 Loyea 原版完全一致。
 *
 * 坐标系使用 CSS 像素(渲染层按 density 缩放)，投影 scale = 4.5/(4.5-z)，
 * unit = min(width*.232, height*.24)，中心在 (w/2, h*.48)。
 * 加性混色下绘制顺序不影响结果，因此无需逐帧深度排序。
 */
class NeuralLivingScene {

    companion object {
        const val ACTIVITY_REST = 0.65f      // 静静陪伴
        const val ACTIVITY_LISTEN = 1.05f    // 倾听
        const val ACTIVITY_THINK = 1.65f     // 思考

        private const val TAU = Math.PI.toFloat() * 2f
        private const val NUCLEUS_COUNT = 135
        private const val SHELL_COUNT = 610
        private const val NODE_COUNT = NUCLEUS_COUNT + SHELL_COUNT
        private const val BRIDGE_COUNT = 28
        private const val ORBIT_POINTS = 29
        private const val DUST_COUNT = 30
        private const val SPARK_COUNT = 36
        private const val EDGE_CAP = 5

        // layerFrames() 的五层参数：spin / amp / lag / tilt / flex（Web 版定值）
        private val LAYERS = arrayOf(
            floatArrayOf(0.85f, 0.050f, 0.00f, 0.055f, 0.65f),
            floatArrayOf(-0.52f, 0.035f, 0.55f, -0.055f, 0.50f),
            floatArrayOf(0.14f, 0.012f, 1.10f, 0.018f, 0.25f),
            floatArrayOf(-0.13f, 0.008f, 1.50f, 0.025f, 0.20f),
            floatArrayOf(0.08f, 0.006f, 1.90f, -0.020f, 0.15f)
        )
    }

    /** 单次投影结果（共享实例，project* 系列写入后立即消费）。 */
    class Proj {
        var x = 0f; var y = 0f; var scale = 1f; var front = 0f
    }

    // ---- 确定性随机（LCG：seed = imul(seed,1664525)+1013904223，Int 溢出回绕等价） ----
    private var seed = 91237
    private fun rand(): Float {
        seed = seed * 1664525 + 1013904223
        return (seed.toLong() and 0xFFFFFFFFL) / 4294967296f
    }

    // ---- 节点（结构数组） ----
    val nodeCount = NODE_COUNT
    private val nodeX = FloatArray(NODE_COUNT)
    private val nodeY = FloatArray(NODE_COUNT)
    private val nodeZ = FloatArray(NODE_COUNT)
    private val nodePhase = FloatArray(NODE_COUNT)
    private val nodeImportance = FloatArray(NODE_COUNT)
    private val nodeLayer = ByteArray(NODE_COUNT)

    /** 边数组 [a0,b0,a1,b1,...]。 */
    var edges: IntArray = IntArray(0); private set
    val edgeCount get() = edges.size / 2

    // ---- 桥接路径（每条 3 个锚点节点索引，三层各取方向投影最大者） ----
    val bridgeAnchors = IntArray(BRIDGE_COUNT * 3)

    // ---- 轨道弧线（基点已按 rx/rz 旋到位；逐帧再过层帧形变）。lazy：构建依赖下方 rot 缓冲 ----
    class OrbitArc(val layer: Int, val pts: FloatArray)

    val orbits: List<OrbitArc> by lazy { buildOrbits() }

    // ---- 尘埃 / 电火花 ----
    private val dust = FloatArray(DUST_COUNT * 4) // x,y,z,phase
    private val sparkEdge = IntArray(SPARK_COUNT)
    private val sparkU = FloatArray(SPARK_COUNT)
    private val sparkSpeed = FloatArray(SPARK_COUNT)

    // ---- 视角与节律状态 ----
    var time = 0f; private set
    var activity = ACTIVITY_REST; private set
    var targetActivity = ACTIVITY_REST
    var rotationX = 0.16f; private set
    var rotationY = 0.28f; private set
    var targetRotationX = 0.16f
    var targetRotationY = 0.28f
    var paused = false; private set

    // ---- 音频驱动（NeuralPulse 扩展；均为 0 时与原版逐帧等价） ----
    /** 平滑响度 0..1（渲染帧率节拍间由分析线程发布）。 */
    var audioLevel = 0f; private set
    /** 节拍冲击 0..1（渲染侧自行指数衰减后写入）。 */
    var audioBeat = 0f; private set
    /** 本帧呼吸/漂移振幅倍率。 */
    private var ampBoost = 1f

    fun setAudioDrive(level: Float, beat: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        audioBeat = beat.coerceIn(0f, 1f)
    }

    /** 生命周期暂停/恢复（页面不可见即停节律）。 */
    fun setPaused(value: Boolean) { paused = value }

    /** 暂停态下拖拽：立即贴合目标视角并静态重绘。 */
    fun snapRotation() {
        rotationX = targetRotationX
        rotationY = targetRotationY
    }

    // ---- 逐帧缓冲 ----
    private val frameRx = FloatArray(5)
    private val frameRy = FloatArray(5)
    private val frameRz = FloatArray(5)
    private val frameSx = FloatArray(5)
    private val frameSy = FloatArray(5)
    private val frameSz = FloatArray(5)
    private val worldX = FloatArray(NODE_COUNT)
    private val worldY = FloatArray(NODE_COUNT)
    private val worldZ = FloatArray(NODE_COUNT)
    private val pxArr = FloatArray(NODE_COUNT)
    private val pyArr = FloatArray(NODE_COUNT)
    private val scaleArr = FloatArray(NODE_COUNT)
    private val frontArr = FloatArray(NODE_COUNT)
    private val rot = FloatArray(3)
    private val projResult = Proj()

    private var layoutWidth = 0f
    private var layoutHeight = 0f
    private var unit = 0f

    // ---- 神经脉冲信号：沿边逐跳传播的相电流（NeuralPulse 新增） ----
    // 每个信号是一段"电流包"：包络内含若干波峰，整体沿边流动，到节点后逐跳路由。
    // 生命周期很长：跑十几到几十个节点，亮度随路程缓慢衰减，最后才消散。
    private class Signal {
        var edge = 0
        var from = 0
        var u = 0f        // 头部位置 0..1
        var hops = 0
        var maxHops = 40
        var speed = 1.2f
        var length = 0.3f // 电流包长度（u 单位）
        var phase = 0f    // 内部波峰相位
        var strength = 1f // 亮度衰减系数：每跳×0.88~0.98
        var alive = false
    }

    private val SIGNAL_MAX = 30
    private val signals = Array(SIGNAL_MAX) { Signal() }
    private var nodeEdges: Array<IntArray> = arrayOf()
    private var spawnAccum = 0f

    init {
        buildNucleus()
        buildShell()
        buildEdges()
        buildBridges()
        buildDustAndSparks()
        buildNodeEdges()
    }

    private fun buildNodeEdges() {
        val lists = Array(nodeCount) { ArrayList<Int>() }
        var e = 0
        while (e < edges.size) {
            val edgeIdx = e / 2 // edges 存的是 [a0,b0,a1,b1,...]，e 为原始下标，边号 = e/2
            lists[edges[e]].add(edgeIdx)
            lists[edges[e + 1]].add(edgeIdx)
            e += 2
        }
        nodeEdges = Array(nodeCount) { lists[it].toIntArray() }
    }

    private fun otherEnd(edge: Int, node: Int): Int =
        if (edges[edge * 2] == node) edges[edge * 2 + 1] else edges[edge * 2]

    private fun spawnSignal() {
        val s = signals.firstOrNull { !it.alive } ?: return
        s.edge = (rand() * edgeCount).toInt().coerceIn(0, edgeCount - 1)
        s.from = if (rand() < 0.5f) edges[s.edge * 2] else edges[s.edge * 2 + 1]
        s.u = 0f
        s.hops = 0
        s.maxHops = 10 + (rand() * 30).toInt()   // 高速窜行 10~40 段连线
        s.speed = 2.2f + rand() * 2.3f           // 每秒 2~4.5 段：电影式高速突触放电
        s.alive = true
    }

    /**
     * 推进神经脉冲电流包：整体沿边流动，到达节点后跳往相邻下一条边，
     * 1~3 跳后消散。spawn 为生成速率（次/秒），speedMul 为流速倍率——
     * 均由渲染层依据响度/节拍/BPM 实时传入。
     */
    fun advanceSignals(dt: Float, spawn: Float, speedMul: Float) {
        spawnAccum += spawn * dt
        var alive = 0
        for (s in signals) if (s.alive) alive++
        while (spawnAccum >= 1f) {
            spawnAccum -= 1f
            if (alive < SIGNAL_MAX) {
                spawnSignal()
                alive++
            } else spawnAccum = spawnAccum.coerceAtMost(1f)
        }
        for (s in signals) {
            if (!s.alive) continue
            s.u += dt * s.speed * speedMul
            if (s.u >= 1f) {
                val arrived = otherEnd(s.edge, s.from)
                s.from = arrived
                s.hops++
                val opts = nodeEdges[arrived]
                if (s.hops >= s.maxHops || opts.isEmpty()) {
                    s.alive = false
                } else {
                    var next = opts[(rand() * opts.size).toInt().coerceIn(0, opts.size - 1)]
                    if (next == s.edge && opts.size > 1) {
                        next = opts[(rand() * opts.size).toInt().coerceIn(0, opts.size - 1)]
                    }
                    s.edge = next
                    s.u = 0f
                }
            }
        }
    }

    /** 末端淡出系数：生命最后 6 跳渐隐（1=正常，0=消散）。 */
    fun signalFade(i: Int): Float =
        min(1f, (signals[i].maxHops - signals[i].hops) / 6f)

    fun signalMax() = SIGNAL_MAX
    fun signalAlive(i: Int) = signals[i].alive
    fun signalHeadU(i: Int) = signals[i].u
    fun signalLength(i: Int) = signals[i].length
    fun signalPhase(i: Int) = signals[i].phase
    fun signalStrength(i: Int) = signals[i].strength

    /** 第 i 个信号所在边的两个端点投影 → out[0..3]（ax,ay,bx,by）。 */
    fun signalEndPoints(i: Int, out: FloatArray) {
        val s = signals[i]
        val a = edges[s.edge * 2]
        val b = edges[s.edge * 2 + 1]
        val pa = project(worldX[a], worldY[a], worldZ[a])
        out[0] = pa.x; out[1] = pa.y
        val pb = project(worldX[b], worldY[b], worldZ[b])
        out[2] = pb.x; out[3] = pb.y
    }

    /** 诊断用：倾倒全部信号状态。 */
    fun dumpSignals(): String = buildString {
        append("edgeCount=").append(edgeCount).append(' ')
        for ((i, s) in signals.withIndex()) {
            if (s.alive) append("sig#$i[edge=").append(s.edge)
                .append(" from=").append(s.from)
                .append(" u=").append(s.u)
                .append(" hops=").append(s.hops).append("] ")
        }
    }

    /** 第 i 个信号在回退 back（0=头部）处的投影；立即消费共享实例。 */
    fun signalProj(i: Int, back: Float): Proj {
        val s = signals[i]
        val other = otherEnd(s.edge, s.from)
        val u = (s.u - back).coerceIn(0f, 1f)
        return project(
            worldX[s.from] + (worldX[other] - worldX[s.from]) * u,
            worldY[s.from] + (worldY[other] - worldY[s.from]) * u,
            worldZ[s.from] + (worldZ[other] - worldZ[s.from]) * u
        )
    }

    private fun buildOrbits(): List<OrbitArc> {
        val arcs = ArrayList<OrbitArc>(6)
        val params = arrayOf(
            floatArrayOf(1.54f, 1.20f, 0.45f),   // [r, rx, rz]
            floatArrayOf(1.64f, -1.03f, -0.62f)
        )
        for (p in params) {
            val r = p[0]; val rx = p[1]; val rz = p[2]
            repeat(3) { k ->
                val pts = FloatArray(ORBIT_POINTS * 3)
                for (j in 0 until ORBIT_POINTS) {
                    val ang = k * TAU / 3f + j / 28f * 1.55f
                    rotate(r * cos(ang), 0f, r * sin(ang), rx, 0f, rz, rot)
                    pts[j * 3] = rot[0]; pts[j * 3 + 1] = rot[1]; pts[j * 3 + 2] = rot[2]
                }
                arcs.add(OrbitArc(layer = if (r < 1.6f) 3 else 4, pts = pts))
            }
        }
        return arcs
    }

    // 平滑局部隆起与凹陷，保持团簇拓扑及其受光
    private fun organic(x: Float, y: Float, z: Float) {
        val bulge = 1f +
            0.105f * sin(x * 2.8f + z * 1.4f + 0.5f) * cos(y * 2.4f - 0.3f) +
            0.065f * sin(y * 3f - z * 1.8f + 0.6f)
        rot[0] = x * bulge + 0.035f * y * y - 0.025f * z
        rot[1] = y * bulge + 0.05f * sin(x * 1.7f + z * 0.8f)
        rot[2] = z * bulge + 0.035f * sin(y * 2.1f)
    }

    private fun pushNode(x: Float, y: Float, z: Float, group: Int, index: Int) {
        organic(x, y, z)
        nodeX[index] = rot[0]; nodeY[index] = rot[1]; nodeZ[index] = rot[2]
        nodeLayer[index] = when {
            group == 0 -> 0
            sqrt(x * x + (y / 1.10f) * (y / 1.10f) + z * z) < 1.10f -> 1
            else -> 2
        }
        nodePhase[index] = rand() * TAU
        nodeImportance[index] = rand()
    }

    // 紧凑中央核嵌在不规则完整神经体积内；无环带、无扁轴，纵横延展一致
    private fun buildNucleus() {
        for (i in 0 until NUCLEUS_COUNT) {
            val a = rand() * TAU
            val v = rand() * 2f - 1f
            val s = sqrt(1f - v * v)
            val r = 0.45f * (if (rand() < 0.22f) Math.cbrt(rand().toDouble()).toFloat() * 0.8f else 0.84f + rand() * 0.16f)
            pushNode(cos(a) * s * r, v * r, sin(a) * s * r, 0, i)
        }
    }

    private fun buildShell() {
        for (i in 0 until SHELL_COUNT) {
            val a = rand() * TAU
            val v = rand() * 2f - 1f
            val s = sqrt(1f - v * v)
            val phi = acos(v)
            val fold = 1f + 0.085f * sin(a * 5f + phi * 3f) * sin(phi) + 0.045f * cos(phi * 8f - a * 3f)
            val r = (if (rand() < 0.28f) 0.48f + rand() * 0.60f else 1.13f + rand() * 0.15f) * fold
            pushNode(cos(a) * s * r * 1.06f, v * r * 1.10f, sin(a) * s * r, 1, NUCLEUS_COUNT + i)
        }
    }

    // 稳定局部近邻；严格度上限避免中心出现亮网格
    private fun buildEdges() {
        val degree = ByteArray(nodeCount)
        val list = ArrayList<Int>(nodeCount)
        for (i in 0 until nodeCount) {
            val near = ArrayList<Pair<Int, Float>>()
            val ax = nodeX[i]; val ay = nodeY[i]; val az = nodeZ[i]
            for (j in i + 1 until nodeCount) {
                if (nodeLayer[i] != nodeLayer[j]) continue
                val dx = ax - nodeX[j]; val dy = ay - nodeY[j]; val dz = az - nodeZ[j]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                if (d < 0.34f) near.add(j to d)
            }
            near.sortBy { it.second }
            for ((j, _) in near) {
                if (degree[i] >= EDGE_CAP) break
                if (degree[j] >= EDGE_CAP) continue
                list.add(i); list.add(j)
                degree[i]++; degree[j]++
            }
        }
        edges = list.toIntArray()
    }

    // 桥接路径锚定三层真实节点，端点保持连通；黄金角分布方向
    private fun buildBridges() {
        for (k in 0 until BRIDGE_COUNT) {
            val a = k * 2.399963f + (rand() - 0.5f) * 0.2f
            val v = 1f - 2f * (k + 0.5f) / BRIDGE_COUNT
            val s = sqrt(1f - v * v)
            val dx = cos(a) * s; val dy = v; val dz = sin(a) * s
            for (layer in 0 until 3) {
                var best = -Float.MAX_VALUE
                var idx = 0
                for (i in 0 until nodeCount) {
                    if (nodeLayer[i].toInt() != layer) continue
                    val len = sqrt(nodeX[i] * nodeX[i] + nodeY[i] * nodeY[i] + nodeZ[i] * nodeZ[i])
                    val score = (nodeX[i] * dx + nodeY[i] * dy + nodeZ[i] * dz) / len
                    if (score > best) { best = score; idx = i }
                }
                bridgeAnchors[k * 3 + layer] = idx
            }
        }
    }

    private fun buildDustAndSparks() {
        for (i in 0 until DUST_COUNT) {
            dust[i * 4] = (rand() - 0.5f) * 3.7f
            dust[i * 4 + 1] = (rand() - 0.5f) * 3.5f
            dust[i * 4 + 2] = (rand() - 0.5f) * 3.4f
            dust[i * 4 + 3] = rand() * TAU
        }
        for (i in 0 until SPARK_COUNT) {
            sparkEdge[i] = (rand() * (edges.size / 2)).toInt().coerceIn(0, edges.size / 2 - 1)
            sparkU[i] = rand()
            sparkSpeed[i] = 0.15f + rand() * 0.23f
        }
    }

    private fun rotate(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, out: FloatArray) {
        var c = cos(rx); var s = sin(rx)
        val y1 = y * c - z * s
        val z1 = y * s + z * c
        c = cos(ry); s = sin(ry)
        val x2 = x * c + z1 * s
        val z2 = -x * s + z1 * c
        c = cos(rz); s = sin(rz)
        out[0] = x2 * c - y1 * s
        out[1] = x2 * s + y1 * c
        out[2] = z2
    }

    // 单一振荡器驱动所有层；ampBoost 让呼吸/振幅跟随音频响度与节拍膨胀
    private fun computeLayerFrames() {
        val phase = time * TAU / 6.8f
        val drive = time * 0.24f + 0.055f * sin(phase)
        ampBoost = 1f + audioLevel * 0.45f + audioBeat * 0.28f
        for (i in 0 until 5) {
            val l = LAYERS[i]
            val amp = l[1] * ampBoost; val lag = l[2]; val tilt = l[3]; val flex = l[4]
            val scale = 1f + amp * sin(phase - lag)
            frameRx[i] = tilt * sin(drive * 0.6f - lag)
            frameRy[i] = drive * l[0]
            frameRz[i] = 0.035f * flex * sin(phase * 0.5f - lag)
            frameSx[i] = scale
            frameSy[i] = scale + 0.016f * flex * sin(phase - lag - 0.45f)
            frameSz[i] = scale
        }
    }

    // 有界局部漂移：近邻共享带相位偏移的平滑场；外层运动以切向为主，保持轮廓稳定
    private fun driftInto(i: Int) {
        val t = time
        val layer = nodeLayer[i].toInt()
        val amp = (when (layer) { 0 -> 0.010f; 1 -> 0.022f; else -> 0.014f }) *
            (1f + 0.55f * audioLevel + 0.25f * audioBeat)
        val bx = nodeX[i]; val by = nodeY[i]; val bz = nodeZ[i]; val ph = nodePhase[i]
        var dx = 0.72f * sin(0.53f * t + 3.1f * by + 1.2f * bz) + 0.28f * sin(0.67f * t + ph)
        var dy = 0.72f * sin(0.47f * t + 2.8f * bz - 1.4f * bx + 0.7f) + 0.28f * sin(0.59f * t + ph * 1.3f + 1.1f)
        var dz = 0.72f * sin(0.51f * t + 3.0f * bx + 1.1f * by + 1.4f) + 0.28f * sin(0.63f * t + ph * 0.8f + 2.2f)
        val radius = sqrt(bx * bx + by * by + bz * bz)
        if (radius > 0f) {
            val ux = bx / radius; val uy = by / radius; val uz = bz / radius
            val radial = (dx * ux + dy * uy + dz * uz) * (if (layer == 2) 0.90f else 0.55f)
            dx -= radial * ux; dy -= radial * uy; dz -= radial * uz
        }
        val fx = bx + amp * dx; val fy = by + amp * dy; val fz = bz + amp * dz
        rotate(
            fx * frameSx[layer], fy * frameSy[layer], fz * frameSz[layer],
            frameRx[layer], frameRy[layer], frameRz[layer], rot
        )
        worldX[i] = rot[0]; worldY[i] = rot[1]; worldZ[i] = rot[2]
    }

    private fun project(x: Float, y: Float, z: Float, out: Proj) {
        rotate(x, y, z, rotationX, rotationY, 0f, rot)
        val scale = 4.5f / (4.5f - rot[2])
        out.x = layoutWidth / 2f + rot[0] * unit * scale
        out.y = layoutHeight * 0.48f + rot[1] * unit * scale
        out.scale = scale
        out.front = ((rot[2] + 1.5f) / 3f).coerceIn(0f, 1f)
    }

    /** 投影世界坐标点，返回共享结果对象（立即消费）。 */
    fun project(x: Float, y: Float, z: Float): Proj {
        project(x, y, z, projResult)
        return projResult
    }

    /** 画布尺寸（CSS 像素）。 */
    fun setLayout(widthCss: Float, heightCss: Float) {
        if (widthCss != layoutWidth || heightCss != layoutHeight) {
            layoutWidth = widthCss
            layoutHeight = heightCss
            unit = min(widthCss * 0.232f, heightCss * 0.24f)
        }
    }

    /**
     * 推进一帧。音频驱动：响度抬升时间流速，节拍冲击额外瞬时加速，
     * 让整体律动贴住音乐节拍。
     */
    fun advance(dt: Float) {
        activity += (targetActivity - activity) * (1f - exp(-dt * 3f))
        val ease = if (dt > 0f) 1f - exp(-dt * 7f) else 1f
        rotationX += (targetRotationX - rotationX) * ease
        rotationY += (targetRotationY - rotationY) * ease
        if (!paused && dt > 0f) {
            // 时间流：基率放缓、音频贡献轻微——呼吸频率随之变得从容
            time += dt * (0.72f + 0.15f * activity) * (1f + 0.25f * audioBeat + 0.08f * audioLevel)
        }
    }

    /** 重算全部顶点的世界坐标（层帧形变 + 漂移）。 */
    fun computeWorlds() {
        computeLayerFrames()
        for (i in 0 until nodeCount) driftInto(i)
    }

    /** 投影全部节点到缓存（渲染边与节点前调用）。 */
    fun projectAllNodes() {
        for (i in 0 until nodeCount) {
            rotate(worldX[i], worldY[i], worldZ[i], rotationX, rotationY, 0f, rot)
            val scale = 4.5f / (4.5f - rot[2])
            pxArr[i] = layoutWidth / 2f + rot[0] * unit * scale
            pyArr[i] = layoutHeight * 0.48f + rot[1] * unit * scale
            scaleArr[i] = scale
            frontArr[i] = ((rot[2] + 1.5f) / 3f).coerceIn(0f, 1f)
        }
    }

    fun nodeProjX(i: Int) = pxArr[i]
    fun nodeProjY(i: Int) = pyArr[i]
    fun nodeProjScale(i: Int) = scaleArr[i]
    fun nodeProjFront(i: Int) = frontArr[i]
    fun nodePhaseAt(i: Int) = nodePhase[i]
    fun nodeImportanceAt(i: Int) = nodeImportance[i]

    fun worldPoint(out: FloatArray, i: Int) {
        out[0] = worldX[i]; out[1] = worldY[i]; out[2] = worldZ[i]
    }

    fun dustCount() = DUST_COUNT
    fun dustAt(i: Int): Proj = project(dust[i * 4], dust[i * 4 + 1], dust[i * 4 + 2])

    fun sparkCount() = SPARK_COUNT

    /** 推进并投影第 spark 个电火花；节拍轻微提速。 */
    fun sparkAt(spark: Int, dt: Float): Proj {
        var u = sparkU[spark] + dt * sparkSpeed[spark] * activity * (1f + 0.25f * audioBeat)
        if (u >= 1f) {
            u %= 1f
            sparkEdge[spark] = (rand() * edgeCount).toInt().coerceIn(0, edgeCount - 1)
        }
        sparkU[spark] = u
        lastSparkU = u
        val e = sparkEdge[spark] * 2
        val a = edges[e]; val b = edges[e + 1]
        val t = u
        return project(
            worldX[a] + (worldX[b] - worldX[a]) * t,
            worldY[a] + (worldY[b] - worldY[a]) * t,
            worldZ[a] + (worldZ[b] - worldZ[a]) * t
        )
    }

    fun centerX() = layoutWidth / 2f
    fun centerY() = layoutHeight * 0.48f
    fun width() = layoutWidth
    fun height() = layoutHeight
    fun frameScaleX(layer: Int) = frameSx[layer]

    /** 最近一次 sparkAt() 使用的弧长参数（渲染层用于正弦淡入淡出）。 */
    var lastSparkU = 0f; private set

    /** Web 版 deform(p, frames[layer])：层帧缩放 + 层帧旋转。 */
    fun deformPoint(out: FloatArray, x: Float, y: Float, z: Float, layer: Int) {
        rotate(
            x * frameSx[layer], y * frameSy[layer], z * frameSz[layer],
            frameRx[layer], frameRy[layer], frameRz[layer], out
        )
    }
}
