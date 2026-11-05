package com.claudeetf.app.util

/** 技術指標（null 代表資料不足） */
object Indicators {
    fun sma(v: List<Float>, n: Int): List<Float?> {
        val out = ArrayList<Float?>(v.size)
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= n) sum -= v[i - n]
            out += if (i >= n - 1) (sum / n).toFloat() else null
        }
        return out
    }

    fun ema(v: List<Float>, n: Int): List<Float> {
        if (v.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val out = ArrayList<Float>(v.size)
        var prev = v[0].toDouble()
        for (x in v) {
            prev = x * k + prev * (1 - k)
            out += prev.toFloat()
        }
        return out
    }

    /** RSI（Wilder 平滑） */
    fun rsi(c: List<Float>, n: Int = 14): List<Float?> {
        val out = ArrayList<Float?>(c.size)
        if (c.isEmpty()) return out
        var gain = 0.0
        var loss = 0.0
        out += null
        for (i in 1 until c.size) {
            val d = (c[i] - c[i - 1]).toDouble()
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            if (i <= n) {
                gain += g; loss += l
                if (i == n) { gain /= n; loss /= n }
            } else {
                gain = (gain * (n - 1) + g) / n
                loss = (loss * (n - 1) + l) / n
            }
            out += if (i < n) null else if (loss == 0.0) 100f else (100 - 100 / (1 + gain / loss)).toFloat()
        }
        return out
    }

    /** KD（9 日 RSV，K、D 以 1/3 平滑，起始 50） */
    fun kd(h: List<Float>, l: List<Float>, c: List<Float>, n: Int = 9): Pair<List<Float?>, List<Float?>> {
        val ks = ArrayList<Float?>(c.size)
        val ds = ArrayList<Float?>(c.size)
        var k = 50.0
        var d = 50.0
        for (i in c.indices) {
            if (i < n - 1) { ks += null; ds += null; continue }
            var hi = h[i]; var lo = l[i]
            for (j in i - n + 1..i) { if (h[j] > hi) hi = h[j]; if (l[j] < lo) lo = l[j] }
            val rsv = if (hi - lo == 0f) 50.0 else (c[i] - lo) / (hi - lo) * 100.0
            k = k * 2 / 3 + rsv / 3
            d = d * 2 / 3 + k / 3
            ks += k.toFloat(); ds += d.toFloat()
        }
        return ks to ds
    }

    data class Macd(val dif: List<Float>, val dea: List<Float>, val hist: List<Float>)

    fun macd(c: List<Float>): Macd {
        val e12 = ema(c, 12)
        val e26 = ema(c, 26)
        val dif = e12.indices.map { e12[it] - e26[it] }
        val dea = ema(dif, 9)
        return Macd(dif, dea, dif.indices.map { dif[it] - dea[it] })
    }
}
