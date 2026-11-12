package com.claudeetf.app

import com.claudeetf.app.data.EntryAdvisor
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsensusTest {
    @Test fun aiLevelMapping() {
        assertEquals(2, EntryAdvisor.aiLevel("強勢多頭", 80))
        assertEquals(1, EntryAdvisor.aiLevel("強勢多頭", 40))
        assertEquals(-2, EntryAdvisor.aiLevel("偏空警示", 75))
        assertEquals(0, EntryAdvisor.aiLevel("轉弱防守", 30))
        assertEquals(0, EntryAdvisor.aiLevel("區間震盪", 90))
    }
}
