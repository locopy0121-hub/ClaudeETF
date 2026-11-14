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
    /** 從預覽點選項目時以下方面板（編輯工具）開啟 */
    var watchSheet by mutableStateOf(false)
    var holdSheet by mutableStateOf(false)
}

/**
 * 編輯工具開關（如同手機「開發人員選項」）：設置 › 關於與更新 › 連點「版本」5 下開啟，再連點 5 下關閉。
 * 平時關閉：預覽點項目、快速工具列、工具艙、項目細部設定與編輯工作室都不顯示。
 */
object EditorMode {
    private var loaded = false
    private var state by mutableStateOf(false)
    val on: Boolean get() {
        if (!loaded) { loaded = true; state = com.claudeetf.app.data.AppState.cacheGet("editorOn") == "1" }
        return state
    }
    fun set(v: Boolean) {
        loaded = true; state = v
        com.claudeetf.app.data.AppState.cachePut("editorOn", if (v) "1" else "0")
        if (!v) { WallEdit.sel = null; WallEdit.sheet = false; EditSel.watchSheet = false; EditSel.holdSheet = false; EditSel.watch = null; EditSel.hold = null }
    }
    // 連點計數：2 秒內連點才算
    private var taps = 0
    private var last = 0L
    /** 回傳要顯示的提示（null＝不提示） */
    fun tap(): String? {
        val now = System.currentTimeMillis()
        taps = if (now - last > 2000) 1 else taps + 1
        last = now
        if (taps >= 5) { taps = 0; set(!on); return if (on) "已開啟編輯工具" else "已關閉編輯工具" }
        return if (taps >= 2) "再點 ${5 - taps} 下" + (if (on) "關閉" else "開啟") + "編輯工具" else null
    }
}

/** 編輯工具未開啟時的提示 */
const val EDITOR_OFF_HINT = "編輯工具未開啟：設置 › 關於與更新，連點「版本」5 下即可開啟"
