package com.claudeetf.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.claudeetf.app.R

/**
 * App 圖示更換：Manifest 內有 10 個 activity-alias（.Icon0….Icon9），各自掛一個圖示，
 * 同一時間只啟用其中一個。先啟用新的、再停用其他，避免出現「沒有任何啟動圖示」的空窗。
 */
object AppIcons {
    data class Icon(val name: String, val desc: String, val bg: Int, val fg: Int)

    val all = listOf(
        Icon("經典", "深色底．柱狀＋折線", R.drawable.ic_bg_0, R.drawable.ic_fg_0),
        Icon("金幣", "藏青漸層．金幣成長", R.drawable.ic_bg_1, R.drawable.ic_fg_1),
        Icon("紅K", "白底．紅漲綠跌 K 棒", R.drawable.ic_bg_2, R.drawable.ic_fg_2),
        Icon("極簡藍", "純色．上升箭頭", R.drawable.ic_bg_3, R.drawable.ic_fg_3),
        Icon("霓虹", "黑底．發光走勢線", R.drawable.ic_bg_4, R.drawable.ic_fg_4),
        Icon("台美雙色", "紅藍斜分", R.drawable.ic_bg_5, R.drawable.ic_fg_5),
        Icon("資產環圈", "配置甜甜圈", R.drawable.ic_bg_6, R.drawable.ic_fg_6),
        Icon("夕陽山形", "暖色面積走勢", R.drawable.ic_bg_7, R.drawable.ic_fg_7),
        Icon("守護盾", "資產安全", R.drawable.ic_bg_8, R.drawable.ic_fg_8),
        Icon("墨線紙本", "米白手繪線圖", R.drawable.ic_bg_9, R.drawable.ic_fg_9),
    )

    private fun cn(context: Context, i: Int) = ComponentName(context.packageName, "com.claudeetf.app.Icon$i")

    private fun isOn(context: Context, i: Int): Boolean =
        when (context.packageManager.getComponentEnabledSetting(cn(context, i))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> i == 0   // Manifest 預設只有 Icon0 啟用
            else -> false
        }

    fun current(context: Context): Int = all.indices.firstOrNull { isOn(context, it) } ?: 0

    fun apply(context: Context, index: Int) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(cn(context, index), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        all.indices.filter { it != index }.forEach {
            pm.setComponentEnabledSetting(cn(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
