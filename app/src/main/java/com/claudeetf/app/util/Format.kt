package com.claudeetf.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

object Fmt {
    private val symbols = DecimalFormatSymbols(Locale.US)

    /**
     * 行情數字：無條件捨去到 decimals 位（不四捨五入）。
     * 先以 BigDecimal.valueOf 取得最短十進位表示並保留 decimals+3 位，消除浮點誤差（6.8499999…、Yahoo 單精度 7.7399998 視為 6.85、7.74，不會被捨成 6.84、7.73）。
     */
    fun trunc(v: Double, decimals: Int): java.math.BigDecimal =
        java.math.BigDecimal.valueOf(v).setScale(decimals + 3, java.math.RoundingMode.HALF_UP)
            .setScale(decimals, java.math.RoundingMode.DOWN)

    /** 無條件捨去後格式化（行情用） */
    fun numTrunc(v: Double, decimals: Int): String {
        if (v.isNaN() || v.isInfinite()) return "—"
        val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        return DecimalFormat(pattern, symbols).apply { roundingMode = java.math.RoundingMode.DOWN }.format(trunc(v, decimals))
    }

    fun num(v: Double, decimals: Int = 0): String {
        val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        return DecimalFormat(pattern, symbols).format(v)
    }

    /** 帶 ▲▼ 與正負號 */
    fun signed(v: Double, decimals: Int = 0, prefix: String = ""): String {
        if (abs(v) < 1e-9) return "— 0"
        val arrow = if (v > 0) "▲ +" else "▼ −"
        // 有小數位（價格漲跌）依行情規則無條件捨去；整數金額照常四捨五入
        return arrow + prefix + (if (decimals > 0) numTrunc(abs(v), decimals) else num(abs(v), decimals))
    }

    fun pct(v: Double, decimals: Int = 2): String {
        if (abs(v) < 1e-9) return "0.00%"
        val sign = if (v > 0) "+" else "−"
        return sign + numTrunc(abs(v), decimals) + "%"
    }

    fun signedPct(v: Double, decimals: Int = 2): String {
        if (abs(v) < 1e-9) return "— 0.00%"
        val arrow = if (v > 0) "▲ " else "▼ "
        return arrow + pct(v, decimals)
    }

    /** 價格：小數第二位，無條件捨去 */
    fun price(v: Double): String = numTrunc(v, 2)

    /** 毫秒 → 台灣時間；今天顯示 HH:mm，其他日期顯示 M/d HH:mm */
    fun time(ms: Long): String {
        val tpe = java.time.ZoneId.of("Asia/Taipei")
        val t = java.time.Instant.ofEpochMilli(ms).atZone(tpe)
        val today = java.time.LocalDate.now(tpe)
        val pattern = if (t.toLocalDate() == today) "HH:mm" else "M/d HH:mm"
        return t.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }

    fun shares(v: Double): String =
        if (abs(v - Math.rint(v)) < 1e-9) num(v, 0) else DecimalFormat("#,##0.####", symbols).format(v)
}
