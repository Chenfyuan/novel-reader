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
import kotlin.math.hypot

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
 * 把内容绘制成被翻起的纸页：录制内容到图层，沿折线（页角与手指连线的中垂线）切开，
 * 未翻起部分正常绘制；翻起部分沿折线镜像，作为纸张背面（半透明底色 + 透出的反向文字），
 * 并在折痕两侧绘制阴影。折线另一侧露出下层页面。
 */
fun Modifier.pageCurl(state: () -> CurlState?, paper: Color): Modifier = drawWithCache {
    val layer = obtainGraphicsLayer()
    onDrawWithContent {
        val st = state()
        if (st == null) { drawContent(); return@onDrawWithContent }
        layer.record { this@onDrawWithContent.drawContent() }
        val w = size.width; val h = size.height
        val (t, o) = st
        val m = (t + o) / 2f
        val dx = o.x - t.x; val dy = o.y - t.y
        val len = hypot(dx, dy)
        if (len < 1f) { drawLayer(layer); return@onDrawWithContent }
        val n = Offset(dx / len, dy / len)
        val page = listOf(Offset.Zero, Offset(w, 0f), Offset(w, h), Offset(0f, h))
        val keep = clipHalf(page, m, n, keepNegative = true)
        val fold = clipHalf(page, m, n, keepNegative = false)
        // 关于折线的镜像变换
        val d = Offset(-n.y, n.x)
        val a = d.x * d.x - d.y * d.y; val b = 2 * d.x * d.y; val c = b; val e = d.y * d.y - d.x * d.x
        val tx = m.x - (a * m.x + b * m.y); val ty = m.y - (c * m.x + e * m.y)
        fun reflect(p: Offset) = Offset(a * p.x + b * p.y + tx, c * p.x + e * p.y + ty)
        val mat = Matrix(floatArrayOf(a, c, 0f, 0f, b, e, 0f, 0f, 0f, 0f, 1f, 0f, tx, ty, 0f, 1f))
        val flap = clipHalf(clipHalf(clipHalf(clipHalf(fold.map(::reflect), Offset.Zero, Offset(-1f, 0f), true),
            Offset(w, 0f), Offset(1f, 0f), true), Offset.Zero, Offset(0f, -1f), true), Offset(0f, h), Offset(0f, 1f), true)
        val shadow = 28.dp.toPx().coerceAtMost(len / 2 + 1f)

        // 1. 未翻起的部分
        clipPath(pathOf(keep)) { drawLayer(layer) }
        // 2. 翻起部分投在下层页面上的阴影（折线外侧）
        clipPath(pathOf(fold)) {
            drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent), m, m + n * shadow))
        }
        // 3. 纸张背面：镜像内容 + 底色，越靠近折痕越暗
        if (flap.size >= 3) {
            val fp = pathOf(flap)
            clipPath(fp) {
                withTransform({ transform(mat) }) { drawLayer(layer) }
                drawRect(paper.copy(alpha = 0.86f))
                drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent, Color.White.copy(alpha = 0.06f)),
                    m, m - n * (len / 2).coerceAtLeast(1f)))
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
