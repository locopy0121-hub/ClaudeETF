package com.claudeetf.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.claudeetf.app.R

/**
 * App 圖示更換：Manifest 內有 20 個 activity-alias（.Icon0….Icon19），各自掛一個圖示，
 * 同一時間只啟用其中一個。先啟用新的、再停用其他，避免出現「沒有任何啟動圖示」的空窗。
 */
object AppIcons {
    /** 3D 渲染圖示（drawable-nodpi/ic_r_N.webp，自適應圖示整張作背景） */
    data class Icon(val name: String, val res: Int)

    val all = listOf(
        Icon("上漲K棒", R.drawable.ic_r_0),
        Icon("漲停紅K", R.drawable.ic_r_1),
        Icon("突破箭頭", R.drawable.ic_r_2),
        Icon("台美雙K", R.drawable.ic_r_3),
        Icon("黃金交叉", R.drawable.ic_r_4),
        Icon("K線放大鏡", R.drawable.ic_r_5),
        Icon("指數山形", R.drawable.ic_r_6),
        Icon("台北101", R.drawable.ic_r_7),
        Icon("華爾街", R.drawable.ic_r_8),
        Icon("美元金幣", R.drawable.ic_r_9),
        Icon("金幣塔", R.drawable.ic_r_10),
        Icon("ETF籃子", R.drawable.ic_r_11),
        Icon("資產圓餅", R.drawable.ic_r_12),
        Icon("複利曲線", R.drawable.ic_r_13),
        Icon("守護盾K", R.drawable.ic_r_14),
        Icon("行情看板", R.drawable.ic_r_15),
        Icon("全息K線", R.drawable.ic_r_16),
        Icon("晶片K線", R.drawable.ic_r_17),
        Icon("指數儀表", R.drawable.ic_r_18),
        Icon("漲幅百分比", R.drawable.ic_r_19),
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
