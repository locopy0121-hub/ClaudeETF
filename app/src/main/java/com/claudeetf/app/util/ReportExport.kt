package com.claudeetf.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.claudeetf.app.data.DailyReport
import com.claudeetf.app.data.PriceBand
import com.claudeetf.app.data.TwPicks
import com.claudeetf.app.ui.theme.Trend
import java.io.File
import kotlin.math.abs

/**
 * 盤後報表輸出成圖片（PNG 長圖）或 PDF（A4 分頁，不會從圖表中間切開）：
 * 市場總結、加權指數走勢、漲跌家數與漲跌幅分布、類股漲跌、個股排行、看漲／看跌各 25 檔。
 */
object ReportExport {
    private const val W = 1080f
    private const val M = 48f
    private val BG = 0xFF0E1117.toInt()
    private val CARD = 0xFF171B24.toInt()
    private val TEXT = 0xFFE8ECF2.toInt()
    private val TEXT2 = 0xFFC3C9D4.toInt()
    private val MUTED = 0xFF8A93A3.toInt()
    private val GRID = 0xFF2A3040.toInt()
    private val GOLD = 0xFFF2B544.toInt()

    private fun up(v: Double): Int = Trend.fg(v).toArgb()

    /** cont：接在前一個區塊的同一張卡片裡（個股列） */
    private class Block(val h: Float, val card: Boolean = true, val cont: Boolean = false, val draw: Canvas.(Float) -> Unit)

    /** 個股完整解析文字 */
    fun pickDetail(p: TwPicks.Pick): String = buildString {
        append("分析：").append(p.reasons.joinToString("；")).append("。")
        append("\n建議：").append(p.entry).append("（K 線").append(p.dir).append("）")
        if (p.entryReason.isNotBlank()) append(" ").append(p.entryReason)
        if (p.stop != null || p.tp1 != null) {
            append("\n操作參考：")
            p.stop?.let { append("停損 ").append(Fmt.price(it)) }
            p.tp1?.let { append(if (p.stop != null) " · " else "").append("目標 ").append(Fmt.price(it)) }
            p.tp2?.let { append("／").append(Fmt.price(it)) }
            p.ma60Gap?.let { append(" · 距 60 日均線 ").append("%+.1f".format(it)).append("%") }
        }
        p.signal?.let { append("\n策略訊號：").append(it) }
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun layout(text: String, size: Float, color: Int, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint(size, color), width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(6f, 1f).build()

    private fun fit(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun blocks(r: DailyReport.Report): List<Block> {
        val out = ArrayList<Block>()
        val inner = W - M * 2 - 48f
        val x0 = M + 24f
        // ---- 標題
        out += Block(170f, card = false) { y ->
            drawText("台股盤後報表", M, y + 70f, paint(56f, TEXT, bold = true))
            drawText(r.date.replace("-", "/") + "  ·  產生於 " + Fmt.time(r.at) + "  ·  分析 ${r.analyzed} 檔", M, y + 120f, paint(28f, MUTED))
            val mp = paint(32f, android.graphics.Color.WHITE, bold = true)
            val tw = mp.measureText(r.moodLabel) + 32f
            val mc = when { r.mood > 0 -> up(1.0); r.mood < 0 -> up(-1.0); else -> GOLD }
            drawRoundRect(RectF(W - M - tw, y + 36f, W - M, y + 86f), 14f, 14f, fill(mc))
            drawText(r.moodLabel, W - M - tw + 16f, y + 73f, mp)
        }
        // ---- 市場總結
        val sl = layout(r.summary.joinToString("\n") { "• $it" }, 30f, TEXT2, inner.toInt())
        out += Block(sl.height + 110f) { y ->
            drawText("市場總結", x0, y + 56f, paint(34f, TEXT, bold = true))
            save(); translate(x0, y + 80f); sl.draw(this); restore()
        }
        // ---- 前次推薦表現
        r.perf?.let { pf ->
            val gs = listOf("個股看漲" to pf.bull, "個股看跌" to pf.bear, "ETF 看漲" to pf.etfBull, "ETF 看跌" to pf.etfBear).filter { it.second != null }
            out += Block(120f + gs.size * 96f) { y ->
                drawText("推薦表現追蹤", x0, y + 56f, paint(34f, TEXT, bold = true))
                drawText("前次報表 " + pf.fromDate.replace("-", "/") + " 的推薦到本日收盤 · 同期加權 " + "%+.2f".format(pf.market) + "%", x0, y + 96f, paint(22f, MUTED))
                gs.forEachIndexed { i, (t, g0) ->
                    val g = g0!!
                    val yy = y + 140f + i * 96f
                    val bull = t.contains("漲")
                    drawText(t, x0, yy, paint(26f, TEXT, bold = true))
                    drawText("%+.2f".format(g.avg) + "%", x0 + 260f, yy, paint(26f, up(g.avg), bold = true, mono = true))
                    drawText((if (bull) "上漲 " else "下跌 ") + "${g.win}/${g.n}（" + "%.0f".format(g.winRate) + "%）", x0 + inner, yy, paint(22f, TEXT2, align = Paint.Align.RIGHT))
                    drawRect(x0, yy + 14f, x0 + inner, yy + 24f, fill(GRID))
                    drawRect(x0, yy + 14f, x0 + (inner * g.winRate / 100).toFloat(), yy + 24f, fill(if (g.winRate >= 50) up(if (bull) 1.0 else -1.0) else MUTED))
                    drawText(fit("最佳：" + g.best.joinToString("、") { it.first + " " + "%+.1f".format(it.second) + "%" } +
                        "　最差：" + g.worst.joinToString("、") { it.first + " " + "%+.1f".format(it.second) + "%" }, paint(20f, TEXT2), inner), x0, yy + 54f, paint(20f, TEXT2))
                }
            }
        }
        // ---- 加權指數走勢
        out += Block(560f) { y ->
            drawText("加權指數", x0, y + 56f, paint(34f, TEXT, bold = true))
            val vp = paint(40f, up(r.twiiChg), bold = true, mono = true, align = Paint.Align.RIGHT)
            drawText("%,.2f".format(r.twii), W - M - 24f, y + 58f, vp)
            drawText("%+,.2f".format(r.twiiChg) + "（" + "%+.2f".format(r.twiiPct) + "%）" +
                (r.otcIdx?.let { "   櫃買 " + "%,.2f".format(it) + "（" + "%+.2f".format(r.otcPct ?: 0.0) + "%）" } ?: ""),
                W - M - 24f, y + 100f, paint(26f, up(r.twiiChg), mono = true, align = Paint.Align.RIGHT))
            val v = r.twiiSeries
            if (v.size >= 2) {
                val top = y + 130f; val h = 360f; val left = x0; val wd = inner
                val mn = v.min(); val mx = v.max(); val rg = (mx - mn).takeIf { it > 0 } ?: 1.0
                fun px(i: Int) = left + i * wd / (v.size - 1)
                fun py(d: Double) = (top + h * (1 - (d - mn) / rg)).toFloat()
                val gp = fill(GRID).apply { strokeWidth = 2f }
                for (k in 0..4) { val yy = top + h * k / 4; drawLine(left, yy, left + wd, yy, gp)
                    drawText("%,.0f".format(mx - rg * k / 4), left + wd, yy - 6f, paint(20f, MUTED, align = Paint.Align.RIGHT)) }
                val col = up(v.last() - v.first())
                val path = Path(); v.forEachIndexed { i, d -> if (i == 0) path.moveTo(px(i), py(d)) else path.lineTo(px(i), py(d)) }
                val area = Path(path).apply { lineTo(px(v.size - 1), top + h); lineTo(left, top + h); close() }
                drawPath(area, fill(col).apply { alpha = 40 })
                drawPath(path, fill(col).apply { style = Paint.Style.STROKE; strokeWidth = 5f })
                if (v.size >= 20) {
                    val ma = Path()
                    for (i in 19 until v.size) { val m = v.subList(i - 19, i + 1).average(); if (i == 19) ma.moveTo(px(i), py(m)) else ma.lineTo(px(i), py(m)) }
                    drawPath(ma, fill(GOLD).apply { style = Paint.Style.STROKE; strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f) })
                }
                drawCircle(px(v.size - 1), py(v.last()), 8f, fill(col))
                drawText("近 ${v.size} 個交易日收盤 · 黃色虛線為月線", left, top + h + 40f, paint(22f, MUTED))
            }
        }
        // ---- 漲跌家數與分布
        out += Block(640f) { y ->
            drawText("漲跌家數", x0, y + 56f, paint(34f, TEXT, bold = true))
            val tot = (r.up + r.down + r.flat).coerceAtLeast(1)
            var x = x0; val bt = y + 80f
            listOf(r.up to up(1.0), r.flat to MUTED, r.down to up(-1.0)).forEach { (n, c) ->
                val w = inner * n / tot; drawRect(x, bt, x + w, bt + 30f, fill(c)); x += w
            }
            drawText("上漲 ${r.up}", x0, bt + 72f, paint(28f, up(1.0), bold = true))
            drawText("平盤 ${r.flat}", W / 2, bt + 72f, paint(28f, MUTED, align = Paint.Align.CENTER))
            drawText("下跌 ${r.down}", x0 + inner, bt + 72f, paint(28f, up(-1.0), bold = true, align = Paint.Align.RIGHT))
            drawText("漲停 ${r.limitUp} 家   跌停 ${r.limitDown} 家   成交 上市 " + "%,.0f".format(r.tseValue) + " 億 · 上櫃 " + "%,.0f".format(r.otcValue) + " 億",
                x0, bt + 118f, paint(26f, TEXT2))
            drawText("漲跌幅分布（個股與 ETF，%）", x0, bt + 176f, paint(28f, TEXT, bold = true))
            val ht = bt + 200f; val hh = 270f
            val mx = (r.hist.maxOrNull() ?: 1).coerceAtLeast(1)
            val bw = inner / r.hist.size.coerceAtLeast(1)
            r.hist.forEachIndexed { i, n ->
                val bh = (hh - 30f) * n / mx
                val c = when { i < 5 -> up(-1.0); i == 5 -> MUTED; else -> up(1.0) }
                drawRect(x0 + i * bw + bw * 0.12f, ht + hh - bh, x0 + i * bw + bw * 0.88f, ht + hh, fill(c))
                drawText(n.toString(), x0 + i * bw + bw / 2, ht + hh - bh - 8f, paint(20f, TEXT2, align = Paint.Align.CENTER))
                drawText(DailyReport.HIST_LABELS.getOrElse(i) { "" }, x0 + i * bw + bw / 2, ht + hh + 30f, paint(20f, MUTED, align = Paint.Align.CENTER))
            }
        }
        // ---- 三大法人
        if (r.hasInst) {
            out += Block(330f + 10 * 46f) { y ->
                drawText("三大法人買賣超（億元，收盤價估算）", x0, y + 56f, paint(34f, TEXT, bold = true))
                val mx = listOf(r.foreignYi, r.trustYi, r.dealerYi).maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
                listOf("外資" to r.foreignYi, "投信" to r.trustYi, "自營商" to r.dealerYi).forEachIndexed { i, (n, v) ->
                    val yy = y + 96f + i * 48f
                    drawText(n, x0, yy + 22f, paint(26f, TEXT))
                    val bx = x0 + 120f; val bw = inner - 120f - 170f; val mid = bx + bw / 2
                    val len = (bw / 2 * abs(v) / mx).toFloat()
                    if (v >= 0) drawRect(mid, yy + 4f, mid + len, yy + 28f, fill(up(v))) else drawRect(mid - len, yy + 4f, mid, yy + 28f, fill(up(v)))
                    drawText("%+,.1f".format(v), x0 + inner, yy + 24f, paint(24f, up(v), mono = true, align = Paint.Align.RIGHT))
                }
                val cw = inner / 2
                listOf("外資買超" to r.fBuy, "外資賣超" to r.fSell).forEachIndexed { k, (t, l) ->
                    val cx = x0 + k * cw
                    drawText(t, cx, y + 280f, paint(26f, MUTED, bold = true))
                    l.take(10).forEachIndexed { i, m ->
                        val yy = y + 322f + i * 46f
                        drawText(fit(m.name, paint(24f, TEXT), cw - 170f), cx, yy, paint(24f, TEXT))
                        drawText("%+,.2f".format(m.valueYi) + "億", cx + cw - 20f, yy, paint(22f, up(m.valueYi), mono = true, align = Paint.Align.RIGHT))
                    }
                }
            }
        }
        // ---- 類股
        if (r.sectors.isNotEmpty()) {
            val list = if (r.sectors.size <= 16) r.sectors else r.sectors.take(8) + r.sectors.takeLast(8)
            val maxAbs = r.sectors.maxOf { abs(it.second) }.takeIf { it > 0 } ?: 1.0
            out += Block(100f + list.size * 46f) { y ->
                drawText("類股漲跌" + if (r.sectors.size > 16) "（前後各 8 名）" else "", x0, y + 56f, paint(34f, TEXT, bold = true))
                val nameW = 200f; val valW = 130f
                val bx = x0 + nameW; val bw = inner - nameW - valW; val mid = bx + bw / 2
                list.forEachIndexed { i, (n, p) ->
                    val yy = y + 96f + i * 46f
                    drawText(fit(n, paint(24f, TEXT), nameW - 12f), x0, yy + 22f, paint(24f, TEXT))
                    val len = (bw / 2 * abs(p) / maxAbs).toFloat()
                    val c = up(p)
                    if (p >= 0) drawRect(mid, yy + 4f, mid + len, yy + 26f, fill(c)) else drawRect(mid - len, yy + 4f, mid, yy + 26f, fill(c))
                    drawLine(mid, yy, mid, yy + 30f, fill(GRID).apply { strokeWidth = 2f })
                    drawText("%+.2f".format(p) + "%", x0 + inner, yy + 22f, paint(24f, c, mono = true, align = Paint.Align.RIGHT))
                }
            }
        }
        // ---- 個股排行（三欄）
        out += Block(110f + 10 * 50f + 20f) { y ->
            drawText("個股排行（成交 ≥ 500 張）", x0, y + 56f, paint(34f, TEXT, bold = true))
            val cw = inner / 3
            listOf("漲幅" to r.gainers, "跌幅" to r.losers, "成交值" to r.actives).forEachIndexed { k, (t, l) ->
                val cx = x0 + k * cw
                drawText(t, cx, y + 100f, paint(26f, MUTED, bold = true))
                l.take(10).forEachIndexed { i, m ->
                    val yy = y + 144f + i * 50f
                    drawText(fit(m.name, paint(24f, TEXT), cw - 120f), cx, yy, paint(24f, TEXT))
                    val v = if (k == 2) "%,.0f".format(m.valueYi) + "億" else "%+.1f".format(m.pct) + "%"
                    drawText(v, cx + cw - 16f, yy, paint(22f, if (k == 2) TEXT2 else up(m.pct), mono = true, align = Paint.Align.RIGHT))
                }
            }
        }
        // ---- 看漲／看跌 25
        fun picks(title: String, list: List<TwPicks.Pick>, bull: Boolean, sub: String = "依日 K 形態與量價、60 日均線、20 日動能、進場等級、一年股價光譜綜合評分") {
            val tone = up(if (bull) 1.0 else -1.0)
            out += Block(130f) { y ->
                drawText(title, x0, y + 56f, paint(34f, tone, bold = true))
                drawText(sub, x0, y + 100f, paint(22f, MUTED))
            }
            if (list.isEmpty()) out += Block(70f) { y -> drawText("沒有符合條件的個股", x0, y + 44f, paint(26f, MUTED)) }
            val mx = list.maxOfOrNull { abs(it.score) }?.takeIf { it > 0 } ?: 1.0
            list.forEachIndexed { i, p ->
                val detail = layout(pickDetail(p), 22f, TEXT2, (inner - 56f).toInt())
                out += Block(76f + detail.height + 22f, cont = true) { y ->
                    if (i > 0) drawLine(x0, y, x0 + inner, y, fill(GRID).apply { strokeWidth = 1.5f })
                    val rk = paint(24f, if (i < 3) android.graphics.Color.WHITE else tone, bold = true, align = Paint.Align.CENTER)
                    drawCircle(x0 + 20f, y + 34f, 20f, fill(tone).apply { alpha = if (i < 3) 255 else 50 })
                    drawText((i + 1).toString(), x0 + 20f, y + 43f, rk)
                    val nx = x0 + 56f
                    val np = paint(28f, TEXT, bold = true)
                    val name = fit(p.name, np, 210f)
                    drawText(name, nx, y + 44f, np)
                    val stk = DailyReport.streakOf(r, p.symbol, bull)
                    val tag = p.symbol + " " + (if (p.otc) "櫃" else "市") + (if (p.zone >= 0) " · " + PriceBand.zoneNames[p.zone] else "") + (if (stk >= 2) " · 連 $stk 日" else "")
                    drawText(tag, nx + np.measureText(name) + 12f, y + 44f, paint(22f, MUTED))
                    // 分數條
                    val bx = x0 + 520f; val bw = 220f
                    drawRect(bx, y + 24f, bx + bw, y + 40f, fill(GRID))
                    drawRect(bx, y + 24f, bx + (bw * abs(p.score) / mx).toFloat().coerceAtLeast(4f), y + 40f, fill(tone))
                    drawText("%+.1f".format(p.score), bx + bw + 12f, y + 42f, paint(22f, tone, mono = true))
                    drawText(Fmt.price(p.price), x0 + inner, y + 32f, paint(24f, TEXT, mono = true, align = Paint.Align.RIGHT))
                    drawText("%+.2f".format(p.pct) + "%", x0 + inner, y + 62f, paint(22f, up(p.pct), mono = true, align = Paint.Align.RIGHT))
                    save(); translate(nx, y + 76f); detail.draw(this); restore()
                }
            }
        }
        picks("看漲・趨勢向上（${r.bulls.size} 檔）", r.bulls, true)
        picks("看跌・避開（${r.bears.size} 檔）", r.bears, false)
        // ---- ETF
        if (r.hasEtf) {
            out += Block(300f + 10 * 50f) { y ->
                drawText("ETF 市場", x0, y + 56f, paint(34f, TEXT, bold = true))
                drawText("上漲 ${r.etfUp} · 平盤 ${r.etfFlat} · 下跌 ${r.etfDown} 檔 · 成交值 " + "%,.0f".format(r.etfValue) + " 億（占上市櫃 " +
                    "%.1f".format(if (r.tseValue + r.otcValue > 0) r.etfValue / (r.tseValue + r.otcValue) * 100 else 0.0) + "%）", x0, y + 100f, paint(24f, TEXT2))
                val tot = (r.etfUp + r.etfDown + r.etfFlat).coerceAtLeast(1)
                var bx = x0
                listOf(r.etfUp to up(1.0), r.etfFlat to MUTED, r.etfDown to up(-1.0)).forEach { (n, c) ->
                    val w = inner * n / tot; drawRect(bx, y + 124f, bx + w, y + 148f, fill(c)); bx += w
                }
                val cw = inner / 3
                listOf("漲幅" to r.etfGainers, "跌幅" to r.etfLosers, "成交值" to r.etfActives).forEachIndexed { k, (t, l) ->
                    val cx = x0 + k * cw
                    drawText(t, cx, y + 200f, paint(26f, MUTED, bold = true))
                    l.take(10).forEachIndexed { i, m ->
                        val yy = y + 244f + i * 50f
                        drawText(fit(m.name, paint(24f, TEXT), cw - 120f), cx, yy, paint(24f, TEXT))
                        val v = if (k == 2) "%,.0f".format(m.valueYi) + "億" else "%+.1f".format(m.pct) + "%"
                        drawText(v, cx + cw - 16f, yy, paint(22f, if (k == 2) TEXT2 else up(m.pct), mono = true, align = Paint.Align.RIGHT))
                    }
                }
            }
            picks("ETF 看漲（${r.etfBulls.size} 檔）", r.etfBulls, true, "ETF 引擎（母子線、上升三法、恐慌止跌、缺口回補）＋均線、動能、一年光譜；不含槓桿與反向")
            picks("ETF 看跌（${r.etfBears.size} 檔）", r.etfBears, false, "ETF 引擎（母子線、上升三法、恐慌止跌、缺口回補）＋均線、動能、一年光譜；不含槓桿與反向")
        }
        out += Block(90f, card = false) { y ->
            drawText("資料：證交所、櫃買中心收盤行情與類股指數、Yahoo 指數走勢。依公開資料計算，僅供參考，非投資建議。", M, y + 50f, paint(22f, MUTED))
        }
        return out
    }

    /** 卡片背景：連續的區塊合併成一張卡片 */
    private fun Canvas.drawBlocks(bs: List<Block>, startY: Float): Float {
        var y = startY
        var i = 0
        while (i < bs.size) {
            if (bs[i].card) {
                var j = i; var h = 0f
                // 標題列與後續的個股列畫在同一張卡片
                h += bs[j].h; j++
                while (j < bs.size && bs[j].cont) { h += bs[j].h; j++ }
                drawRoundRect(RectF(M, y, W - M, y + h + 12f), 28f, 28f, fill(CARD))
                var yy = y
                for (k in i until j) { bs[k].draw(this, yy); yy += bs[k].h }
                y += h + 36f; i = j
            } else { bs[i].draw(this, y); y += bs[i].h; i++ }
        }
        return y
    }

    private fun totalHeight(bs: List<Block>): Float {
        var y = 24f; var i = 0
        while (i < bs.size) {
            if (bs[i].card) {
                var j = i + 1; var h = bs[i].h
                while (j < bs.size && bs[j].cont) { h += bs[j].h; j++ }
                y += h + 36f; i = j
            } else { y += bs[i].h; i++ }
        }
        return y + 24f
    }

    fun png(r: DailyReport.Report): Bitmap {
        val bs = blocks(r)
        val h = totalHeight(bs).toInt()
        val bmp = Bitmap.createBitmap(W.toInt(), h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        c.drawBlocks(bs, 24f)
        return bmp
    }

    /** A4 直式分頁；個股列以外的區塊不跨頁 */
    fun pdf(r: DailyReport.Report, out: File) {
        val bs = blocks(r)
        val pageW = 595; val pageH = 842
        val scale = pageW / W
        val usable = pageH / scale - 48f
        val doc = PdfDocument()
        // 分頁：依序放入，放不下就換頁
        val pages = ArrayList<MutableList<Block>>()
        var cur = ArrayList<Block>(); var used = 24f
        bs.forEach { b ->
            val need = b.h + if (b.card && !b.cont) 36f else 0f
            if (used + need > usable && cur.isNotEmpty()) { pages += cur; cur = ArrayList(); used = 24f }
            cur += b; used += need
        }
        if (cur.isNotEmpty()) pages += cur
        pages.forEachIndexed { idx, list ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create())
            val c = page.canvas
            c.drawColor(BG)
            c.save(); c.scale(scale, scale)
            c.drawBlocks(list, 24f)
            c.drawText("${idx + 1} / ${pages.size}", W - M, pageH / scale - 20f, paint(20f, MUTED, align = Paint.Align.RIGHT))
            c.restore()
            doc.finishPage(page)
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun shareDir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

    /** 產生檔案；pdf = false 時為 PNG */
    fun file(ctx: Context, r: DailyReport.Report, pdf: Boolean): File {
        val f = File(shareDir(ctx), "台股盤後報表_" + r.date + if (pdf) ".pdf" else ".png")
        if (pdf) pdf(r, f) else png(r).let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
        return f
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType(if (f.name.endsWith(".pdf")) "application/pdf" else "image/png")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "分享盤後報表").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 存到手機「下載」資料夾（Android 10 以上）；回傳顯示用路徑 */
    fun saveToDownloads(ctx: Context, f: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, if (f.name.endsWith(".pdf")) "application/pdf" else "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/台美股資產")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: return null
        return "下載/台美股資產/" + f.name
    }
}
