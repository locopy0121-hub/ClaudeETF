package com.claudeetf.app.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 備份與還原：把 App 的本機資料（交易、帳本基準、自選、提醒、稅務設定、淨值紀錄、顯示設定）
 * 匯出成一個 JSON 檔。API 金鑰預設不匯出。
 */
object Backup {
    private const val PREFS = "claudeetf"
    private val secretKeys = setOf("fugleKey", "finnhubKey")

    fun export(context: Context, includeKeys: Boolean = false): String {
        AppState.flush() // 存檔有合併延遲，匯出前先寫入最新變更
        AppState.persist()
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val data = JSONObject()
        p.all.forEach { (k, v) ->
            if (!includeKeys && k in secretKeys) return@forEach
            val entry = JSONObject()
            when (v) {
                is String -> entry.put("t", "s").put("v", v)
                is Int -> entry.put("t", "i").put("v", v)
                is Long -> entry.put("t", "l").put("v", v)
                is Float -> entry.put("t", "f").put("v", v.toDouble())
                is Boolean -> entry.put("t", "b").put("v", v)
                else -> return@forEach
            }
            data.put(k, entry)
        }
        // 金鑰已改存加密儲存，主 prefs 裡不一定有：要匯出時直接取目前值
        if (includeKeys) {
            if (AppState.fugleKey.isNotEmpty()) data.put("fugleKey", JSONObject().put("t", "s").put("v", AppState.fugleKey))
            if (AppState.finnhubKey.isNotEmpty()) data.put("finnhubKey", JSONObject().put("t", "s").put("v", AppState.finnhubKey))
        }
        return JSONObject()
            .put("app", "ClaudeETF")
            .put("format", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("data", data)
            .toString(2)
    }

    /** 還原後重新載入；回傳錯誤訊息或 null */
    fun import(context: Context, text: String): String? {
        val root = runCatching { JSONObject(text) }.getOrElse { return "不是有效的備份檔" }
        if (root.optString("app") != "ClaudeETF") return "這不是本 App 的備份檔"
        val data = root.optJSONObject("data") ?: return "備份檔內容是空的"
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keepKeys = secretKeys.associateWith { p.getString(it, "") ?: "" }
        val e = p.edit().clear()
        data.keys().forEach { k ->
            val entry = data.optJSONObject(k) ?: return@forEach
            // 金鑰寫入加密儲存，不放回明文 prefs
            if (k in secretKeys) { AppState.restoreSecret(context, k, entry.optString("v")); return@forEach }
            when (entry.optString("t")) {
                "s" -> e.putString(k, entry.optString("v"))
                "i" -> e.putInt(k, entry.optInt("v"))
                "l" -> e.putLong(k, entry.optLong("v"))
                "f" -> e.putFloat(k, entry.optDouble("v").toFloat())
                "b" -> e.putBoolean(k, entry.optBoolean("v"))
            }
        }
        // 備份檔沒有金鑰時保留手機上原本的金鑰
        keepKeys.forEach { (k, v) -> if (!data.has(k) && v.isNotEmpty()) e.putString(k, v) }
        e.commit()
        AppState.reload(context)
        return null
    }

    fun fileName(ext: String): String =
        "ClaudeETF-" + LocalDate.now(ZoneId.of("Asia/Taipei")).toString() + "." + ext
}

/**
 * 交易 CSV：匯出與匯入。匯入支援常見券商對帳單欄位名稱（中英文），
 * 日期可用 2025/11/05、2025-11-05、114/11/05（民國年）。
 */
object TradeCsv {
    // 前 9 欄可直接匯回；後面為參考用的試算欄位（匯入時忽略）
    private val header = listOf("日期", "類別", "代號", "股數", "價格", "手續費", "交易稅", "匯率", "備註",
        "名稱", "帳戶", "標籤", "成交金額(原幣)", "淨收付(台幣)", "已實現損益(台幣)", "股息實收(台幣)")

    fun export(trades: List<Trade>): String {
        val sb = StringBuilder("﻿") // BOM，讓 Excel 正確顯示中文
        sb.append(header.joinToString(",")).append("\n")
        trades.filter { it.origin != TradeOrigin.LEGACY }.sortedBy { Ledger.sortKey(it) }.forEach { t ->
            val type = if (t.origin == TradeOrigin.OPENING) "期初" else t.type.label
            val gross = t.gross
            val net = when (t.type) {
                TradeType.BUY -> -(gross + t.fee) * t.fx
                TradeType.SELL -> (gross - t.fee - t.tax) * t.fx
                TradeType.DIVIDEND -> AppState.dividendById[t.id] ?: 0.0
                TradeType.SPLIT -> 0.0
            }
            sb.append(
                listOf(
                    t.date, type, t.symbol, plain(t.shares), plain(t.price), plain(t.fee), plain(t.tax), plain(t.fx), t.note,
                    AppState.quote(t.symbol)?.name ?: "", AppState.account(t.account)?.name ?: "", t.tags.joinToString(" "),
                    plain(gross), plain(Math.round(net * 100) / 100.0),
                    AppState.realizedById[t.id]?.let { plain(Math.round(it * 100) / 100.0) } ?: "",
                    AppState.dividendById[t.id]?.let { plain(Math.round(it * 100) / 100.0) } ?: "",
                ).joinToString(",") { csvCell(it) }
            ).append("\n")
        }
        return sb.toString()
    }

    private fun plain(v: Double) = java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun csvCell(s: String) = if (s.contains(",") || s.contains("\"")) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun splitLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && (c == ',' || c == '\t' || c == ';') -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }

    data class ParseResult(val trades: List<Trade>, val errors: List<String>)

    private fun col(names: List<String>, vararg keys: String): Int =
        names.indexOfFirst { n -> keys.any { k -> n.contains(k, ignoreCase = true) } }

    private fun num(s: String?): Double? =
        s?.replace(",", "")?.replace("$", "")?.replace("NT", "")?.replace("元", "")?.trim()?.toDoubleOrNull()

    fun normalizeDate(raw: String): String? {
        val parts = raw.trim().split('/', '-', '.', ' ').filter { it.isNotBlank() }
        if (parts.size < 3) {
            val d = raw.filter { it.isDigit() }
            if (d.length == 8) return "${d.take(4)}/${d.substring(4, 6)}/${d.takeLast(2)}"
            if (d.length == 7) return "${d.take(3).toInt() + 1911}/${d.substring(3, 5)}/${d.takeLast(2)}"
            return null
        }
        var y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].take(2).toIntOrNull() ?: return null
        if (y < 1911) y += 1911
        if (m !in 1..12 || d !in 1..31) return null
        return "%04d/%02d/%02d".format(y, m, d)
    }

    private fun typeOf(s: String, sharesSign: Double?): TradeType? {
        val v = s.trim().lowercase()
        return when {
            v.contains("賣") || v.contains("sell") || v == "s" -> TradeType.SELL
            v.contains("買") || v.contains("buy") || v == "b" || v.contains("期初") -> TradeType.BUY
            v.contains("息") || v.contains("div") -> TradeType.DIVIDEND
            v.contains("拆") || v.contains("split") -> TradeType.SPLIT
            v.isEmpty() && sharesSign != null -> if (sharesSign < 0) TradeType.SELL else TradeType.BUY
            else -> null
        }
    }

    fun parse(text: String, defaultFx: Double): ParseResult {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        if (lines.size < 2) return ParseResult(emptyList(), listOf("檔案沒有資料列"))
        val names = splitLine(lines[0])
        val iDate = col(names, "日期", "date", "成交日")
        val iType = col(names, "類別", "買賣", "交易別", "type", "side", "action")
        val iSym = col(names, "代號", "symbol", "ticker", "股票代碼", "證券代號")
        val iShares = col(names, "股數", "數量", "shares", "quantity", "qty")
        val iPrice = col(names, "價格", "成交價", "單價", "price")
        val iFee = col(names, "手續費", "fee", "commission")
        val iTax = col(names, "交易稅", "證交稅", "tax")
        val iFx = col(names, "匯率", "fx", "rate")
        val iNote = col(names, "備註", "note", "memo")
        val missing = listOfNotNull(
            if (iDate < 0) "日期" else null, if (iSym < 0) "代號" else null,
            if (iShares < 0) "股數" else null, if (iPrice < 0) "價格" else null,
        )
        if (missing.isNotEmpty()) return ParseResult(emptyList(), listOf("找不到欄位：" + missing.joinToString("、") + "（第一列需為標題）"))

        val out = ArrayList<Trade>()
        val errors = ArrayList<String>()
        for ((n, line) in lines.drop(1).withIndex()) {
            val c = splitLine(line)
            fun at(i: Int) = if (i in c.indices) c[i] else null
            val rowNo = n + 2
            val date = at(iDate)?.let { normalizeDate(it) }
            val sym = at(iSym)?.trim()?.uppercase()?.substringBefore(" ")?.removeSuffix(".TW")?.removeSuffix(".TWO")
            val sharesRaw = num(at(iShares))
            val price = num(at(iPrice))
            val type = typeOf(at(iType) ?: "", sharesRaw)
            when {
                date == null -> errors += "第 $rowNo 列：日期無法辨識"
                sym.isNullOrBlank() -> errors += "第 $rowNo 列：缺少代號"
                sharesRaw == null || price == null -> errors += "第 $rowNo 列：股數或價格不是數字"
                type == null -> errors += "第 $rowNo 列：無法判斷買進或賣出"
                else -> {
                    val symbol: String = sym!!
                    val isUs = !symbol.first().isDigit()
                    out += Trade(
                        type!!, symbol, date!!, kotlin.math.abs(sharesRaw!!), price!!,
                        if (isUs) num(at(iFx)) ?: defaultFx else 1.0,
                        num(at(iFee)) ?: 0.0, num(at(iTax)) ?: 0.0,
                        note = at(iNote) ?: "",
                    )
                }
            }
        }
        return ParseResult(out, errors)
    }
}
