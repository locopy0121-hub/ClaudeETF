package com.claudeetf.app

import com.claudeetf.app.alerts.AlertEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeWrapTest {
    @Test fun splitsSentencesAndLists() {
        val t = AlertEngine.wrap("待記錄：0056 除息、00878 除息、2330 拆股；打開持股頁一鍵記錄。")
        assertEquals("待記錄：\n・0056 除息\n・00878 除息\n・2330 拆股\n打開持股頁一鍵記錄。", t)
    }

    @Test fun keepsShortText() {
        assertEquals("通知功能正常。", AlertEngine.wrap("通知功能正常。"))
    }
}
