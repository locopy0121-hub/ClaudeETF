package com.claudeetf.app.util

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 美股交易時段，全部換算成台灣時間，夏令／冬令由時區規則自動處理 */
object UsSession {
    private val NY: ZoneId = ZoneId.of("America/New_York")
    private val TPE: ZoneId = ZoneId.of("Asia/Taipei")
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // 2026–2027 美股全日休市（紐約日期）
    val holidayNames: Map<LocalDate, String> = listOf(
        "2026-11-26" to "感恩節", "2026-12-25" to "聖誕節",
        "2027-01-01" to "元旦", "2027-01-18" to "馬丁路德金恩紀念日", "2027-02-15" to "總統日",
        "2027-03-26" to "耶穌受難日", "2027-05-31" to "陣亡將士紀念日", "2027-06-18" to "六月節（補假）",
        "2027-07-05" to "獨立紀念日（補假）", "2027-09-06" to "勞動節", "2027-11-25" to "感恩節",
        "2027-12-24" to "聖誕節（補假）",
    ).associate { LocalDate.parse(it.first) to it.second }
    private val holidays = holidayNames.keys

    /** 提早收盤日（紐約日期，美東 13:00 收盤、盤後至 17:00） */
    val earlyCloses: Map<LocalDate, String> = listOf(
        "2026-11-27" to "感恩節隔日", "2026-12-24" to "平安夜", "2027-11-26" to "感恩節隔日",
    ).associate { LocalDate.parse(it.first) to it.second }

    /** FOMC 利率決議（第二天美東 14:00 公布）；聯準會公告日程 */
    val fomcDecisionDays: List<LocalDate> = listOf(
        "2026-12-09",
        "2027-01-27", "2027-03-17", "2027-04-28", "2027-06-09",
        "2027-07-28", "2027-09-15", "2027-10-27", "2027-12-08",
    ).map { LocalDate.parse(it) }

    /** 紐約某日某時 → 台灣時間 */
    fun nyToTaipei(date: LocalDate, time: LocalTime): ZonedDateTime =
        ZonedDateTime.of(date, time, NY).withZoneSameInstant(TPE)

    enum class Phase(val label: String) {
        PRE("盤前交易中"),
        REGULAR("正規交易中"),
        POST("盤後交易中"),
        CLOSED("已收盤"),
        HOLIDAY("今日休市"),
    }

    data class Info(
        val phase: Phase,
        val isDst: Boolean,
        val preTw: String,
        val openTw: String,
        val closeTw: String,
        val postEndTw: String,
        /** 正規時段進度 0–1 */
        val progress: Float,
        val countdown: String,
    )

    private fun tw(date: LocalDate, t: LocalTime): String =
        ZonedDateTime.of(date, t, NY).withZoneSameInstant(TPE).format(HHMM)

    fun info(now: ZonedDateTime = ZonedDateTime.now()): Info {
        val ny = now.withZoneSameInstant(NY)
        val d = ny.toLocalDate()
        val t = ny.toLocalTime()
        val isDst = NY.rules.isDaylightSavings(ny.toInstant())
        val weekend = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        val open = LocalTime.of(9, 30)
        val early = d in earlyCloses
        val close = if (early) LocalTime.of(13, 0) else LocalTime.of(16, 0)
        val postEnd = if (early) LocalTime.of(17, 0) else LocalTime.of(20, 0)
        val phase = when {
            d in holidays -> Phase.HOLIDAY
            weekend -> Phase.CLOSED
            t >= LocalTime.of(4, 0) && t < open -> Phase.PRE
            t >= open && t < close -> Phase.REGULAR
            t >= close && t < postEnd -> Phase.POST
            else -> Phase.CLOSED
        }
        val progress = if (phase == Phase.REGULAR)
            Duration.between(open, t).toMinutes() / Duration.between(open, close).toMinutes().toFloat() else 0f
        val countdown = when (phase) {
            Phase.REGULAR -> {
                val m = Duration.between(t, close).toMinutes()
                "距收盤 ${m / 60} 小時 ${m % 60} 分" + if (early) "（提早收盤）" else ""
            }
            Phase.PRE -> {
                val m = Duration.between(t, open).toMinutes()
                "距開盤 ${m / 60} 小時 ${m % 60} 分"
            }
            else -> (if (isDst) "夏令時間" else "冬令時間") + if (early) " · 今日提早收盤" else ""
        }
        return Info(
            phase, isDst,
            tw(d, LocalTime.of(4, 0)), tw(d, open), tw(d, close), tw(d, postEnd),
            progress, countdown,
        )
    }
}
