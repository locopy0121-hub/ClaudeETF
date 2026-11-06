package com.claudeetf.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.ColorMode
import com.claudeetf.app.data.Market
import java.io.File
import java.time.LocalDate

/** 持股快照分享圖：配置比例與前十大持股（可隱藏金額） */
object Snapshot {
    private const val W = 1080

    fun share(context: Context, hideAmounts: Boolean) {
        val bmp = render(hideAmounts)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "portfolio-" + LocalDate.now() + ".png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "分享持股快照").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun up(v: Double): Int {
        val red = 0xFFFF6B6B.toInt(); val green = 0xFF3DD68C.toInt()
        val tw = AppState.colorMode == ColorMode.TW
        return when { v > 0 -> if (tw) red else green; v < 0 -> if (tw) green else red; else -> 0xFF9AA3B2.toInt() }
    }

    fun render(hide: Boolean): Bitmap {
        val hs = AppState.holdings.toList().sortedByDescending { AppState.marketValueTwd(it) }
        val top = hs.take(10)
        val h = 560 + top.size * 92 + 120
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = 0xFF0E1117.toInt(); val card = 0xFF171B24.toInt(); val text = 0xFFE8ECF2.toInt(); val muted = 0xFF9AA3B2.toInt()
        c.drawColor(bg)
        fun p(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val left = 64f
        c.drawText("我的投資組合", left, 120f, p(54f, text, bold = true))
        c.drawText(LocalDate.now().toString() + " · 台美股資產", left, 172f, p(32f, muted))

        val mv = hs.sumOf { AppState.marketValueTwd(it) }
        val cost = hs.sumOf { AppState.costTwd(it) }
        val total = mv + AppState.cashTwd
        val unreal = mv - cost
        val pct = if (cost > 0) unreal / cost * 100 else 0.0
        val today = AppState.todayPnlTwd()
        val todayPct = if (total - today > 0) today / (total - today) * 100 else 0.0

        val r = RectF(48f, 210f, W - 48f, 470f)
        c.drawRoundRect(r, 32f, 32f, Paint().apply { color = card })
        c.drawText("總資產（TWD）", left + 16, 270f, p(30f, muted))
        c.drawText(if (hide) "＊＊＊＊＊＊" else Fmt.num(total), left + 16, 350f, p(72f, text, bold = true, mono = true))
        c.drawText("今日 " + Fmt.signedPct(todayPct), left + 16, 420f, p(34f, up(today), mono = true))
        c.drawText("報酬 " + Fmt.signedPct(pct), left + 420, 420f, p(34f, up(unreal), mono = true))

        // 配置條
        val tw = AppState.totalMarketValueTwd(Market.TW); val us = AppState.totalMarketValueTwd(Market.US)
        val y0 = 510f
        var x = 48f
        val bw = W - 96f
        listOf(tw to 0xFFF2B544.toInt(), us to 0xFFB9A6FF.toInt(), AppState.cashTwd to 0xFF5B6475.toInt()).forEach { (v, col) ->
            if (total > 0 && v > 0) {
                val w = (v / total * bw).toFloat()
                c.drawRect(x, y0, x + w, y0 + 18f, Paint().apply { color = col }); x += w
            }
        }
        val legend = "台股 " + Fmt.num(if (total > 0) tw / total * 100 else 0.0, 1) + "%   美股 " +
            Fmt.num(if (total > 0) us / total * 100 else 0.0, 1) + "%   現金 " + Fmt.num(if (total > 0) AppState.cashTwd / total * 100 else 0.0, 1) + "%"
        c.drawText(legend, left, y0 + 64f, p(30f, muted))

        var y = y0 + 130f
        top.forEach { hd ->
            val q = AppState.quote(hd.symbol)
            val w = if (mv > 0) AppState.marketValueTwd(hd) / mv * 100 else 0.0
            val chg = q?.changePct ?: 0.0
            val ret = if (hd.avgCost > 0 && q != null && q.price > 0) (q.price / hd.avgCost - 1) * 100 else 0.0
            c.drawText(hd.symbol, left, y, p(36f, text, bold = true, mono = true))
            c.drawText((q?.name ?: "").take(10), left + 210, y, p(32f, muted))
            c.drawText(Fmt.num(w, 1) + "%", W - 470f, y, p(34f, text, mono = true))
            c.drawText(Fmt.signedPct(chg), W - 330f, y, p(30f, up(chg), mono = true))
            c.drawText(Fmt.pct(ret, 1), W - 150f, y, p(30f, up(ret), mono = true))
            c.drawRect(left, y + 18f, left + ((W - 128f) * (w / 100)).toFloat(), y + 26f, Paint().apply { color = 0xFF6EA8FF.toInt() })
            y += 92f
        }
        c.drawText("權重 · 今日 · 報酬率", W - 470f, y + 10f, p(26f, muted))
        c.drawText("數據僅供參考，非投資建議", left, h - 40f, p(26f, muted))
        return bmp
    }
}
