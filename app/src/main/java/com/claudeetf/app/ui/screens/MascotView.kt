package com.claudeetf.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos

/**
 * 動畫角色：資料由預覽頁的 SVG 轉出（MascotData，viewBox 200×230），這裡解析成節點樹後以 Canvas 繪製。
 * 動畫依 CSS 預覽的關鍵影格重現；進場等級越低，動作越慢、幅度越小（觀望放慢、偏空幾乎停住）。
 */
object Mascots {
    val names get() = MascotData.names

    sealed class N
    class Shape(
        val t: Char, val v: FloatArray, val path: Path?, val text: String?, val bold: Boolean,
        val fill: Color?, val stroke: Color?, val sw: Float, val round: Boolean, val dash: FloatArray?, val op: Float, val tf: FloatArray?,
    ) : N()
    class Group(val anim: String?, val ox: Float, val oy: Float, val delay: Float, val tf: FloatArray?, val kids: List<N>) : N()

    private val cache = HashMap<Int, Group>()

    fun tree(i: Int): Group = cache.getOrPut(i) { group(JSONObject(MascotData.json[i])) }

    private fun fa(a: JSONArray?) = a?.let { FloatArray(it.length()) { k -> it.getDouble(k).toFloat() } }
    private fun color(s: String?) = s?.let { Color(it.toLong(16)) }

    private fun group(o: JSONObject): Group {
        val k = o.getJSONArray("k")
        val kids = (0 until k.length()).map { i -> k.getJSONObject(i).let { if (it.has("k")) group(it) else shape(it) } }
        val org = fa(o.optJSONArray("o"))
        return Group(o.optString("a").ifBlank { null }, org?.get(0) ?: 100f, org?.get(1) ?: 115f, o.optDouble("dl", 0.0).toFloat(), fa(o.optJSONArray("tf")), kids)
    }

    private fun shape(o: JSONObject): Shape {
        val t = o.getString("t")[0]
        return Shape(
            t, fa(o.optJSONArray("v")) ?: FloatArray(0),
            if (t == 'p') runCatching { PathParser().parsePathString(o.getString("d")).toPath() }.getOrNull() else null,
            o.optString("tx").takeIf { t == 'x' }, o.optBoolean("b"),
            color(o.optString("f").ifBlank { null }), color(o.optString("s").ifBlank { null }),
            o.optDouble("w", 1.0).toFloat(), o.optInt("rd") == 1, fa(o.optJSONArray("da")), o.optDouble("op", 1.0).toFloat(), fa(o.optJSONArray("tf")),
        )
    }

    // ---------------------------------------------------------------- 動畫

    /** 0→1→0 的平滑往返 */
    private fun wave(p: Float) = (0.5f - 0.5f * cos(2 * PI * p).toFloat())

    private fun lerpKeys(p: Float, keys: List<Pair<Float, Float>>): Float {
        for (i in 1 until keys.size) {
            val (t0, v0) = keys[i - 1]; val (t1, v1) = keys[i]
            if (p <= t1) return v0 + (v1 - v0) * if (t1 > t0) (p - t0) / (t1 - t0) else 0f
        }
        return keys.last().second
    }

    /** 動畫狀態：位移、旋轉、縮放、透明度 */
    class Tf(var tx: Float = 0f, var ty: Float = 0f, var rot: Float = 0f, var sx: Float = 1f, var sy: Float = 1f, var alpha: Float = 1f)

    private val dur = mapOf(
        "bob" to 2.4f, "jump" to 1.6f, "blink" to 3.6f, "wave" to 1.2f, "wag" to 0.9f, "float" to 2.8f, "waddle" to 1.1f,
        "glow" to 1.1f, "pop" to 1.6f, "shadow" to 2.4f, "shadowj" to 1.6f, "beckon" to 0.9f, "tilt" to 2.6f, "lift" to 1.4f,
        "flame" to 0.22f, "flip" to 2.2f, "hop" to 1.4f, "drop" to 1.8f, "sway" to 2.2f, "grow" to 2.2f, "orbit" to 4f,
        "wiggle" to 1f, "spin" to 8f, "drift" to 3f,
    )

    /** speed：時間倍率（< 1 變慢）；amp：動作幅度 */
    fun anim(a: String, time: Float, delay: Float, speed: Float, amp: Float): Tf {
        val d = dur[a] ?: 2f
        val t = ((time * speed - delay) / d).let { it - kotlin.math.floor(it) }
        val r = Tf()
        when (a) {
            "bob" -> r.ty = -6f * wave(t) * amp
            "float" -> r.ty = -10f * wave(t) * amp
            "jump" -> r.ty = lerpKeys(t, listOf(0f to 0f, .2f to 2f, .38f to -22f, .6f to 0f, 1f to 0f)) * amp
                .also { r.sy = lerpKeys(t, listOf(0f to 1f, .2f to .94f, .38f to 1.03f, .6f to 1f, 1f to 1f)) }
            "hop" -> r.ty = lerpKeys(t, listOf(0f to 0f, .3f to -18f, .55f to 0f, 1f to 0f)) * amp
            "lift" -> { r.ty = lerpKeys(t, listOf(0f to 0f, .25f to -14f, .5f to -8f, .75f to -16f, 1f to 0f)) * amp
                r.rot = lerpKeys(t, listOf(0f to -2f, .25f to 2f, .5f to -1f, .75f to 1f, 1f to -2f)) * amp }
            "blink" -> r.sy = lerpKeys(t, listOf(0f to 1f, .92f to 1f, .95f to .1f, 1f to 1f))
            "wave" -> r.rot = (-8f + 30f * wave(t)) * amp
            "wag" -> r.rot = (-14f + 30f * wave(t)) * amp
            "waddle" -> r.rot = (-6f + 12f * wave(t)) * amp
            "tilt" -> r.rot = (-5f + 10f * wave(t)) * amp
            "sway" -> r.rot = (-5f + 10f * wave(t)) * amp
            "wiggle" -> r.rot = (-8f + 16f * wave(t)) * amp
            "beckon" -> r.rot = -16f * wave(t) * amp
            "glow" -> r.alpha = 0.35f + 0.65f * wave(t)
            "pop" -> { val s = 1f + 0.06f * wave(t) * amp; r.sx = s; r.sy = s }
            "grow" -> { val s = 0.92f + 0.14f * wave(t) * amp; r.sx = s; r.sy = s }
            "shadow", "shadowj" -> { r.sx = 1f - 0.2f * wave(t) * amp; r.alpha = 0.35f - 0.13f * wave(t) }
            "flame" -> r.sy = 1f + 0.35f * wave(t) * amp
            "flip" -> r.sx = lerpKeys(t, listOf(0f to 1f, .25f to (1f - .85f * amp), .5f to 1f, 1f to 1f))
            "drop" -> { r.ty = lerpKeys(t, listOf(0f to -40f, .6f to 16f, .7f to 22f, 1f to 22f))
                r.alpha = lerpKeys(t, listOf(0f to 0f, .2f to 1f, .6f to 1f, .7f to 0f, 1f to 0f)) }
            "orbit", "spin" -> r.rot = 360f * t
            "drift" -> r.tx = (-4f + 8f * wave(t)) * amp
        }
        return r
    }

    // ---------------------------------------------------------------- 繪製

    fun DrawScope.drawMascot(i: Int, time: Float, level: Int) {
        val g = tree(i)
        val k = minOf(size.width / 200f, size.height / 230f)
        val speed = when { level >= 2 -> 1f; level == 1 -> 0.85f; level == 0 -> 0.55f; level == -1 -> 0.38f; else -> 0.3f }
        val amp = when { level >= 1 -> 1f; level == 0 -> 0.6f; level == -1 -> 0.35f; else -> 0.25f }
        withTransform({
            translate((size.width - 200f * k) / 2f, (size.height - 230f * k) / 2f)
            scale(k, k, Offset.Zero)
        }) { drawGroup(g, time, speed, amp, 1f) }
    }

    private fun DrawScope.applyStatic(tf: FloatArray?, block: DrawScope.() -> Unit) {
        if (tf == null) { block(); return }
        withTransform({
            if (tf[0] == 0f) rotate(tf[1], Offset(tf[2], tf[3])) else translate(tf[1], tf[2])
        }) { block() }
    }

    private fun DrawScope.drawGroup(g: Group, time: Float, speed: Float, amp: Float, alpha: Float) {
        applyStatic(g.tf) {
            val a = g.anim
            if (a == null) { g.kids.forEach { drawNode(it, time, speed, amp, alpha) }; return@applyStatic }
            val tf = anim(a, time, g.delay, speed, amp)
            val pivot = Offset(g.ox, g.oy)
            withTransform({
                translate(tf.tx, tf.ty)
                if (tf.rot != 0f) rotate(tf.rot, pivot)
                if (tf.sx != 1f || tf.sy != 1f) scale(tf.sx, tf.sy, pivot)
            }) { g.kids.forEach { drawNode(it, time, speed, amp, alpha * tf.alpha) } }
        }
    }

    private fun DrawScope.drawNode(n: N, time: Float, speed: Float, amp: Float, alpha: Float) {
        when (n) {
            is Group -> drawGroup(n, time, speed, amp, alpha)
            is Shape -> applyStatic(n.tf) { drawShape(n, alpha * n.op) }
        }
    }

    private val textPaint by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }

    private fun DrawScope.drawShape(s: Shape, alpha: Float) {
        val v = s.v
        val stroke = s.stroke?.let {
            Stroke(s.sw, cap = if (s.round) StrokeCap.Round else StrokeCap.Butt, join = if (s.round) StrokeJoin.Round else StrokeJoin.Miter,
                pathEffect = s.dash?.let { d -> PathEffect.dashPathEffect(if (d.size == 1) floatArrayOf(d[0], d[0]) else d) })
        }
        fun both(draw: (Color, androidx.compose.ui.graphics.drawscope.DrawStyle) -> Unit) {
            s.fill?.let { draw(it, Fill) }
            if (stroke != null) draw(s.stroke!!, stroke)
        }
        when (s.t) {
            'c' -> both { c, st -> drawCircle(c, v[2], Offset(v[0], v[1]), alpha.coerceIn(0f, 1f), st) }
            'e' -> both { c, st -> drawOval(c, Offset(v[0] - v[2], v[1] - v[3]), Size(v[2] * 2, v[3] * 2), alpha.coerceIn(0f, 1f), st) }
            'r' -> both { c, st -> drawRoundRect(c, Offset(v[0], v[1]), Size(v[2], v[3]), CornerRadius(v[4], v[4]), st, alpha.coerceIn(0f, 1f)) }
            'l' -> s.stroke?.let { c ->
                drawLine(c, Offset(v[0], v[1]), Offset(v[2], v[3]), s.sw, if (s.round) StrokeCap.Round else StrokeCap.Butt,
                    s.dash?.let { d -> PathEffect.dashPathEffect(if (d.size == 1) floatArrayOf(d[0], d[0]) else d) }, alpha.coerceIn(0f, 1f))
            }
            'p' -> s.path?.let { p -> both { c, st -> drawPath(p, c, alpha.coerceIn(0f, 1f), st) } }
            'x' -> {
                val paint = textPaint
                paint.textSize = v[2]
                paint.isFakeBoldText = s.bold
                paint.color = (s.fill ?: Color.Black).copy(alpha = alpha.coerceIn(0f, 1f)).let {
                    android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
                }
                drawContext.canvas.nativeCanvas.drawText(s.text ?: "", v[0], v[1], paint)
            }
        }
    }
}

/** 角色動畫元件：level 為進場等級（2 建議進場 … -2 不宜進場） */
@androidx.compose.runtime.Composable
fun MascotView(index: Int, level: Int, modifier: Modifier) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
    }
    val idx = index.coerceIn(0, MascotData.json.size - 1)
    Canvas(modifier) { with(Mascots) { drawMascot(idx, time.floatValue, level) } }
}
