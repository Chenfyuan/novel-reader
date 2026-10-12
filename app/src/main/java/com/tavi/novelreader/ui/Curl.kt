package com.tavi.novelreader.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 仿真翻页的几何状态：t 为翻起的页角当前位置，o 为页角原始位置（右上或右下角） */
data class CurlState(val t: Offset, val o: Offset)

private fun clipHalf(poly: List<Offset>, m: Offset, n: Offset, keepNegative: Boolean): List<Offset> {
    fun side(p: Offset): Float { val d = (p.x - m.x) * n.x + (p.y - m.y) * n.y; return if (keepNegative) d else -d }
    val out = ArrayList<Offset>(poly.size + 2)
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        val da = side(a); val db = side(b)
        if (da <= 0) out += a
        if ((da < 0 && db > 0) || (da > 0 && db < 0)) { val k = da / (da - db); out += a + (b - a) * k }
    }
    return out
}

private fun pathOf(p: List<Offset>) = Path().apply {
    if (p.isNotEmpty()) { moveTo(p[0].x, p[0].y); p.drop(1).forEach { lineTo(it.x, it.y) }; close() }
}

/**
 * 把内容绘制成弯曲翻起的纸页（圆柱卷曲模型）：
 * 页角与手指连线方向为 n，卷曲轴垂直于 n。轴内侧保持平整；轴外侧的纸沿半径 R 的圆柱卷起，
 * 前四分之一圈是正面（越弯越暗），后四分之一圈与圆柱之后的平直部分是纸张背面（透出反向文字）。
 * 圆柱用若干条平行于轴的窄带近似，每条窄带是一次仿射变换后的图层重放，开销与带数成正比。
 * 轴的位置取使页角正好落在手指处，因此手感与原来的直线折页一致。
 */
fun Modifier.pageCurl(state: () -> CurlState?, paper: Color): Modifier = drawWithCache {
    val layer = obtainGraphicsLayer()
    onDrawWithContent {
        val st = state()
        if (st == null) { drawContent(); return@onDrawWithContent }
        layer.record { this@onDrawWithContent.drawContent() }
        val w = size.width; val h = size.height
        val (t, o) = st
        val dx = o.x - t.x; val dy = o.y - t.y
        val len = hypot(dx, dy)
        if (len < 1f) { drawLayer(layer); return@onDrawWithContent }
        val n = Offset(dx / len, dy / len)
        val r = (len / 6f).coerceIn(4.dp.toPx(), 40.dp.toPx())
        val arc = PI.toFloat() * r
        // 页角到轴的距离 D 满足：页角卷过去后落在手指处，D + (D - πR) = len
        val dCorner = (len + arc) / 2f
        val axis = o - n * dCorner
        val an = axis.x * n.x + axis.y * n.y
        val page = listOf(Offset.Zero, Offset(w, 0f), Offset(w, h), Offset(0f, h))
        fun band(d0: Float, d1: Float) = clipHalf(clipHalf(page, axis + n * d0, n, keepNegative = false), axis + n * d1, n, keepNegative = true)
        /** 源坐标 d∈[d0,d1] 线性映射到投影坐标 [x0,x1] 的仿射矩阵 */
        fun strip(d0: Float, d1: Float, x0: Float, x1: Float): Pair<Matrix, (Offset) -> Offset> {
            val k = (x1 - x0) / (d1 - d0)
            val q = k - 1f
            val c = -q * an + x0 - k * d0
            val m = Matrix(floatArrayOf(
                1 + q * n.x * n.x, q * n.x * n.y, 0f, 0f,
                q * n.x * n.y, 1 + q * n.y * n.y, 0f, 0f,
                0f, 0f, 1f, 0f,
                n.x * c, n.y * c, 0f, 1f))
            return m to { p: Offset -> val d = p.x * n.x + p.y * n.y; p + n * (q * d + c) }
        }
        fun proj(d: Float) = if (d <= arc) r * sin(d / r) else -(d - arc)

        // 1. 平整部分
        clipPath(pathOf(clipHalf(page, axis, n, keepNegative = true))) { drawLayer(layer) }
        // 2. 卷起的纸投在下层页面上的阴影（圆柱最外侧之外）
        clipPath(pathOf(clipHalf(page, axis + n * r, n, keepNegative = false))) {
            val s = 24.dp.toPx()
            drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent), axis + n * r, axis + n * (r + s)))
        }
        // 3. 由内到外绘制窄带：后绘制的（背面）盖在前面
        val steps = 20
        val end = minOf(dCorner, arc)
        for (i in 0 until steps) {
            val d0 = end * i / steps; val d1 = end * (i + 1) / steps
            val poly = band(d0, d1); if (poly.size < 3) continue
            val (mat, map) = strip(d0, d1, proj(d0), proj(d1))
            val mid = (d0 + d1) / 2 / r
            val back = mid > PI / 2
            clipPath(pathOf(poly.map(map))) {
                withTransform({ transform(mat) }) { drawLayer(layer) }
                if (back) drawRect(paper.copy(alpha = 0.86f))
                // 光照：正面越接近垂直越暗，背面靠近顶端最亮
                val shade = if (!back) (1f - cos(mid)) * 0.35f else (0.18f * abs(cos(mid)))
                drawRect(Color.Black.copy(alpha = shade.coerceIn(0f, 0.5f)))
            }
        }
        // 4. 圆柱之后平直的背面
        if (dCorner > arc) {
            val poly = band(arc, dCorner + len)
            if (poly.size >= 3) {
                val (mat, map) = strip(arc, arc + 1f, 0f, -1f)
                clipPath(pathOf(poly.map(map))) {
                    withTransform({ transform(mat) }) { drawLayer(layer) }
                    drawRect(paper.copy(alpha = 0.86f))
                    drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Transparent), axis, axis - n * (dCorner - arc).coerceAtLeast(1f)))
                }
            }
        }
    }
}

/**
 * 仿真翻页容器：拖动右侧向前翻，拖动左侧向后翻（上一页从左边「翻回来」），松手时根据位置与方向完成或回弹。
 * page(i) 渲染第 i 页；越界时调用 onPrev / onNext（切换章节）。
 */
@Composable
fun CurlPager(
    index: Int, count: Int, onIndex: (Int) -> Unit,
    onPrev: () -> Unit, onNext: () -> Unit, paper: Color,
    turnRequests: kotlinx.coroutines.flow.Flow<Int>,
    preview: CurlState? = null,
    page: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val scope = rememberCoroutineScope()
        var dir by remember { mutableIntStateOf(if (preview != null) 1 else 0) }
        var corner by remember { mutableStateOf(Offset(w, h)) }
        val t = remember { Animatable(preview?.t ?: Offset(w, h), Offset.VectorConverter) }
        val curIndex by rememberUpdatedState(index)
        val spineMax = w

        fun constrain(p: Offset): Offset {
            // 纸张不能从书脊处撕开：页角到书脊端点的距离不超过页宽
            val s = Offset(0f, corner.y)
            val v = p - s; val l = v.getDistance()
            return if (l > spineMax) s + v * (spineMax / l) else p
        }
        suspend fun finish(forward: Boolean) {
            val y = corner.y
            if (dir == 1) {
                if (forward) { t.animateTo(Offset(-w, y), tween(320)); onIndex(curIndex + 1) } else t.animateTo(Offset(w, y), tween(260))
            } else if (dir == -1) {
                if (forward) { t.animateTo(Offset(w, y), tween(320)); onIndex(curIndex - 1) } else t.animateTo(Offset(-w, y), tween(260))
            }
            dir = 0
        }
        fun begin(d: Int, y: Float): Boolean {
            if (d == 1 && curIndex >= count - 1) { onNext(); return false }
            if (d == -1 && curIndex <= 0) { onPrev(); return false }
            corner = Offset(w, if (y < h / 2) 0f else h)
            dir = d
            return true
        }
        LaunchedEffect(turnRequests) {
            turnRequests.collect { d ->
                if (dir != 0) return@collect
                if (!begin(d, h)) return@collect
                t.snapTo(if (d == 1) Offset(w, h) else Offset(-w, h))
                finish(true)
            }
        }
        val curDir by rememberUpdatedState(dir)
        val gestures = Modifier.pointerInput(count, w, h) {
            var startX = 0f
            detectDragGestures(
                onDragStart = { startX = it.x },
                onDragEnd = { scope.launch { val x = t.value.x; finish(if (curDir == 1) x < w * 0.5f else x > -w * 0.2f) } },
                onDragCancel = { scope.launch { finish(false) } },
                onDrag = { change, delta ->
                    change.consume()
                    if (curDir == 0) {
                        val d = if (delta.x < 0) 1 else -1
                        if (!begin(d, change.position.y)) return@detectDragGestures
                    }
                    val p = change.position
                    val target = if (curDir == 1) p else Offset(2 * p.x - w, p.y)
                    scope.launch { t.snapTo(constrain(target)) }
                },
            )
        }
        Box(Modifier.fillMaxSize().then(gestures)) {
            when (dir) {
                1 -> {
                    Box(Modifier.fillMaxSize()) { page(index + 1) }
                    Box(Modifier.fillMaxSize().pageCurl({ CurlState(t.value, corner) }, paper)) { page(index) }
                }
                -1 -> {
                    Box(Modifier.fillMaxSize()) { page(index) }
                    Box(Modifier.fillMaxSize().pageCurl({ CurlState(t.value, corner) }, paper)) { page(index - 1) }
                }
                else -> Box(Modifier.fillMaxSize()) { page(index) }
            }
        }
    }
}
