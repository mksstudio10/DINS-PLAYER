package com.dinsplayer.app

import com.dinsplayer.app.features.mobile.isWideNav
import org.junit.Assert.assertFalse
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
}
