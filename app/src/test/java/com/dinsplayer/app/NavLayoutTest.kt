package com.dinsplayer.app

import com.dinsplayer.app.features.mobile.TAB_BERANDA
import com.dinsplayer.app.features.mobile.TAB_LIVETV
import com.dinsplayer.app.features.mobile.TAB_SETELAN
import com.dinsplayer.app.features.mobile.initialTabForLayout
import com.dinsplayer.app.features.mobile.isWideNav
import com.dinsplayer.app.features.mobile.resolveBackTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLayoutTest {

    // Mirrors android.content.res.Configuration. Literals instead of the constants so this test
    // stays a plain JVM test with no Android stub on the classpath.
    private val undefined = 0
    private val portrait = 1
    private val landscape = 2

    @Test
    fun tvAlwaysUsesRail() {
        assertTrue(isWideNav(isTv = true, orientation = undefined))
        assertTrue(isWideNav(isTv = true, orientation = portrait))
        assertTrue(isWideNav(isTv = true, orientation = landscape))
    }

    @Test
    fun phonePortraitKeepsBottomBar() {
        assertFalse(isWideNav(isTv = false, orientation = portrait))
        assertFalse(isWideNav(isTv = false, orientation = undefined))
    }

    @Test
    fun phoneLandscapeUsesRail() {
        assertTrue(isWideNav(isTv = false, orientation = landscape))
    }

    @Test
    fun wideOrTvOpensDirectlyOnLiveTvAndPortraitPhoneOpensOnBeranda() {
        assertEquals(TAB_LIVETV, initialTabForLayout(wide = true))
        assertEquals(TAB_BERANDA, initialTabForLayout(wide = false))
    }

    @Test
    fun backReturnsToLayoutHomeTabBeforePromptingExit() {
        assertEquals(TAB_LIVETV, resolveBackTab(currentTab = TAB_SETELAN, homeTab = TAB_LIVETV))
        assertEquals(TAB_LIVETV, resolveBackTab(currentTab = TAB_BERANDA, homeTab = TAB_LIVETV))
        assertNull(resolveBackTab(currentTab = TAB_LIVETV, homeTab = TAB_LIVETV))

        assertEquals(TAB_BERANDA, resolveBackTab(currentTab = TAB_SETELAN, homeTab = TAB_BERANDA))
        assertNull(resolveBackTab(currentTab = TAB_BERANDA, homeTab = TAB_BERANDA))
    }
}
