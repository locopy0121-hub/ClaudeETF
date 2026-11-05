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
    private val holidays = setOf(
        "2026-11-26", "2026-12-25",
        "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31",
        "2027-06-18", "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24",
    ).map { LocalDate.parse(it) }.toSet()

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
        val close = LocalTime.of(16, 0)
        val phase = when {
            d in holidays -> Phase.HOLIDAY
            weekend -> Phase.CLOSED
            t >= LocalTime.of(4, 0) && t < open -> Phase.PRE
            t >= open && t < close -> Phase.REGULAR
            t >= close && t < LocalTime.of(20, 0) -> Phase.POST
            else -> Phase.CLOSED
        }
        val progress = if (phase == Phase.REGULAR)
            Duration.between(open, t).toMinutes() / 390f else 0f
        val countdown = when (phase) {
            Phase.REGULAR -> {
                val m = Duration.between(t, close).toMinutes()
                "距收盤 ${m / 60} 小時 ${m % 60} 分"
            }
            Phase.PRE -> {
                val m = Duration.between(t, open).toMinutes()
                "距開盤 ${m / 60} 小時 ${m % 60} 分"
            }
            else -> if (isDst) "夏令時間" else "冬令時間"
        }
        return Info(
            phase, isDst,
            tw(d, LocalTime.of(4, 0)), tw(d, open), tw(d, close), tw(d, LocalTime.of(20, 0)),
            progress, countdown,
        )
    }
}
