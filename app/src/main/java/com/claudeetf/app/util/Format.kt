package com.claudeetf.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

object Fmt {
    private val symbols = DecimalFormatSymbols(Locale.US)

    fun num(v: Double, decimals: Int = 0): String {
        val pattern = if (decimals <= 0) "#,##0" else "#,##0." + "0".repeat(decimals)
        return DecimalFormat(pattern, symbols).format(v)
    }

    /** 帶 ▲▼ 與正負號 */
    fun signed(v: Double, decimals: Int = 0, prefix: String = ""): String {
        if (abs(v) < 1e-9) return "— 0"
        val arrow = if (v > 0) "▲ +" else "▼ −"
        return arrow + prefix + num(abs(v), decimals)
    }

    fun pct(v: Double, decimals: Int = 2): String {
        if (abs(v) < 1e-9) return "0.00%"
        val sign = if (v > 0) "+" else "−"
        return sign + num(abs(v), decimals) + "%"
    }

    fun signedPct(v: Double, decimals: Int = 2): String {
        if (abs(v) < 1e-9) return "— 0.00%"
        val arrow = if (v > 0) "▲ " else "▼ "
        return arrow + pct(v, decimals)
    }

    fun price(v: Double): String = num(v, 2)

    fun shares(v: Double): String =
        if (abs(v - Math.rint(v)) < 1e-9) num(v, 0) else DecimalFormat("#,##0.####", symbols).format(v)
}
