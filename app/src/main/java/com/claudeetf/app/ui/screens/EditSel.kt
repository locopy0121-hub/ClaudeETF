package com.claudeetf.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 樣式編輯器的選取狀態：預覽固定在頂部（另一個清單項目）時，預覽與下方設定共用同一個選取 */
object EditSel {
    /** 行情卡片："c:項目" 卡片、"l:項目" 清單 */
    var watch by mutableStateOf<String?>(null)
    /** 行情卡片預覽顯示：0 卡片、1 清單 */
    var watchView by mutableStateOf(0)
    /** 持股卡片選取的項目 */
    var hold by mutableStateOf<String?>(null)
}
