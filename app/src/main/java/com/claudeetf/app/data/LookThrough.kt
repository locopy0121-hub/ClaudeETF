package com.claudeetf.app.data

/**
 * 穿透持股（look-through）：把持有的 ETF 依成分權重拆回個股，與直接持有的個股合併。
 *
 * 公式：
 *   經由 ETF 的曝險（元）＝ ETF 市值（元）× 成分權重（%）÷ 100
 *   個股總曝險           ＝ 直接持有市值 ＋ Σ 各 ETF 曝險
 *   占組合比例（%）      ＝ 個股總曝險 ÷ 組合總市值 × 100
 *   未揭露／其他          ＝ ETF 市值 × (100 − Σ已揭露權重) ÷ 100（權重合計不足 100% 的部分，含現金、期貨）
 *   兩檔 ETF 重疊度（%） ＝ Σ min(權重A_s, 權重B_s)（s 為兩者共同持有的個股）
 * 捨入：全程以 Double 計算不捨入；畫面顯示金額時元以下無條件捨去、百分比捨去至小數第 2 位。
 * 純計算、無 Android 相依，可單元測試。
 */
object LookThrough {
    /** 一個持有部位；comp 為 ETF 成分（代號、名稱、權重%），個股為 null */
    data class Position(val symbol: String, val name: String, val value: Double, val comp: List<Triple<String, String, Double>>?)

    data class Exposure(val symbol: String, val name: String, val direct: Double, val via: Map<String, Double>) {
        val total: Double get() = direct + via.values.sum()
    }

    data class Result(
        val total: Double,
        val exposures: List<Exposure>,
        /** 各 ETF 未揭露／非個股（現金、期貨等）部分（元） */
        val other: Map<String, Double>,
        /** ETF 兩兩重疊度（代號A、代號B、重疊%） */
        val overlap: List<Triple<String, String, Double>>,
    )

    fun compute(positions: List<Position>): Result {
        val total = positions.sumOf { it.value.coerceAtLeast(0.0) }
        val direct = HashMap<String, Double>()
        val via = HashMap<String, HashMap<String, Double>>()
        val names = HashMap<String, String>()
        val other = LinkedHashMap<String, Double>()
        positions.forEach { p ->
            if (p.value <= 0) return@forEach
            val comp = p.comp
            if (comp == null) {
                direct[p.symbol] = (direct[p.symbol] ?: 0.0) + p.value
                names.putIfAbsent(p.symbol, p.name)
                return@forEach
            }
            var covered = 0.0
            comp.forEach { (sym, name, w) ->
                if (sym.isBlank() || w <= 0) return@forEach        // 現金、期貨等非個股 → 計入其他
                covered += w
                val amt = p.value * w / 100
                via.getOrPut(sym) { HashMap() }.merge(p.symbol, amt, Double::plus)
                names.putIfAbsent(sym, name)
            }
            val rest = p.value * (100 - covered).coerceAtLeast(0.0) / 100
            if (rest > 0) other[p.symbol] = rest
        }
        val keys = direct.keys + via.keys
        val exposures = keys.map { s -> Exposure(s, names[s] ?: s, direct[s] ?: 0.0, via[s] ?: emptyMap()) }
            .sortedByDescending { it.total }
        val etfs = positions.filter { it.comp != null && it.value > 0 }
        val overlap = ArrayList<Triple<String, String, Double>>()
        for (i in etfs.indices) for (j in i + 1 until etfs.size) {
            val a = etfs[i].comp!!.filter { it.first.isNotBlank() }.associate { it.first to it.third }
            val b = etfs[j].comp!!.filter { it.first.isNotBlank() }.associate { it.first to it.third }
            val ov = a.keys.intersect(b.keys).sumOf { minOf(a[it]!!, b[it]!!) }
            overlap += Triple(etfs[i].symbol, etfs[j].symbol, ov)
        }
        return Result(total, exposures, other, overlap.sortedByDescending { it.third })
    }
}
