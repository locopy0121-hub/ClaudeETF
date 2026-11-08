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
        Icon("鉻金柱", R.drawable.ic_r_0),
        Icon("全息球", R.drawable.ic_r_1),
        Icon("玻璃K棒", R.drawable.ic_r_2),
        Icon("霓虹環", R.drawable.ic_r_3),
        Icon("金屬箭頭", R.drawable.ic_r_4),
        Icon("液態金屬", R.drawable.ic_r_5),
        Icon("能量核心", R.drawable.ic_r_6),
        Icon("數據立方", R.drawable.ic_r_7),
        Icon("星環行星", R.drawable.ic_r_8),
        Icon("黑曜鑽", R.drawable.ic_r_9),
        Icon("雷射山脈", R.drawable.ic_r_10),
        Icon("熔岩球", R.drawable.ic_r_11),
        Icon("DNA螺旋", R.drawable.ic_r_12),
        Icon("極光玻璃", R.drawable.ic_r_13),
        Icon("電路晶片", R.drawable.ic_r_14),
        Icon("雙球台美", R.drawable.ic_r_15),
        Icon("黃金階梯", R.drawable.ic_r_16),
        Icon("粒子漩渦", R.drawable.ic_r_17),
        Icon("鈦金幣", R.drawable.ic_r_18),
        Icon("全息立方", R.drawable.ic_r_19),
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
