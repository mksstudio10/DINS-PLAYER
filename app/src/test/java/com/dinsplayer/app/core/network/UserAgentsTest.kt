package com.dinsplayer.app.core.network

import com.dinsplayer.app.data.models.UserAgentOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentsTest {

    private val expectedPremium =
        "DinsPlayerPremium/2.0 (Android; Premium; ExoPlayer) Chrome/129.0.0.0 Safari/537.36"

    @Test
    fun chainOrderIsGlobalOneGlobalTwoThenPremium() {
        val chain = UserAgents.fetchChain()
        assertEquals(UserAgents.GLOBAL_1, chain[0])
        assertEquals(UserAgents.GLOBAL_2, chain[1])
        assertEquals(expectedPremium, chain[2])
        assertEquals(3, chain.size)
    }

    @Test
    fun preferredAgentComesFirstWithoutDuplicates() {
        val chain = UserAgents.fetchChain(preferred = UserAgents.GLOBAL_2)
        assertEquals(listOf(UserAgents.GLOBAL_2, UserAgents.GLOBAL_1, expectedPremium), chain)
    }

    @Test
    fun legacyAgentsStayAfterPremium() {
        val chain = UserAgents.fetchChain(legacy = listOf("TiviMate", ""))
        assertEquals(expectedPremium, chain[2])
        assertEquals(listOf("TiviMate", ""), chain.drop(3))
    }

    @Test
    fun premiumIsRecognisedOnlyForItself() {
        assertTrue(UserAgents.isPremium(expectedPremium))
        assertFalse(UserAgents.isPremium(UserAgents.GLOBAL_2))
        assertFalse(UserAgents.isPremium(null))
    }

    @Test
    fun premiumIsNotOfferedInTheStreamTestPicker() {
        assertTrue(UserAgentOption.values().none { it.value.contains("Premium", ignoreCase = true) })
    }
}

