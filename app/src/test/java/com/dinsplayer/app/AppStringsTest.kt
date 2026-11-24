package com.dinsplayer.app

import com.dinsplayer.app.core.i18n.AppLanguage
import com.dinsplayer.app.core.i18n.AppLocaleState
import com.dinsplayer.app.core.i18n.S
import com.dinsplayer.app.core.i18n.stringsForLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsTest {

    @Test
    fun supportsNineLanguagesWithEnglishAsDefault() {
        assertEquals(9, AppLanguage.entries.size)
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("unknown"))

        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromCode("id"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromCode("es"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromCode("fr"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromCode("de"))
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromCode("it"))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.fromCode("pt"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromCode("ru"))
        assertEquals(AppLanguage.ARABIC, AppLanguage.fromCode("ar"))
    }

    @Test
    fun onlyArabicIsRtl() {
        assertTrue(AppLanguage.ARABIC.isRtl)
        AppLanguage.entries.filter { it != AppLanguage.ARABIC }.forEach { lang ->
            assertFalse("${lang.code} should be LTR", lang.isRtl)
        }
    }

    @Test
    fun allLanguagesProvideCompleteNonBlankStringsAndFormatters() {
        AppLanguage.entries.forEach { lang ->
            val s = stringsForLanguage(lang)
            assertTrue(lang.nativeName.isNotBlank())
            assertTrue(lang.englishName.isNotBlank())
            assertTrue(s.cancel.isNotBlank())
            assertTrue(s.save.isNotBlank())
            assertTrue(s.apply.isNotBlank())
            assertTrue(s.navHome.isNotBlank())
            assertTrue(s.navSettings.isNotBlank())
            assertTrue(s.languageTitle.isNotBlank())
            assertTrue(s.languageDialogTitle.isNotBlank())
            assertTrue(s.themeColorTitle.isNotBlank())
            assertTrue(s.epgSourceTitle.isNotBlank())
            assertTrue(s.streamTestTitle.isNotBlank())
            assertTrue(s.pause.isNotBlank())
            assertEquals(AppLanguage.entries.size, s.languageNames.size)
            AppLanguage.entries.forEach { target ->
                assertTrue(s.languageNames[target]?.isNotBlank() == true)
            }
            assertTrue(s.fmtChannels(5).contains("5"))
            assertTrue(s.fmtSelectedOf4(2).contains("2"))
            assertTrue(s.fmtSlotN(1).contains("1"))
            assertTrue(s.fmtBufferSeconds(32).contains("32"))
            assertTrue(s.fmtActivePlaylist("Demo").contains("Demo"))
            assertTrue(s.fmtStartMulti(3).contains("3"))
            assertTrue(s.fmtEpgSummaryActiveOnly(2, 2).contains("2"))
            assertTrue(s.fmtEpgActiveHttp(200).contains("200"))
            assertTrue(s.fmtEpgActivePrograms(15).contains("15"))
        }
    }

    @Test
    fun reactiveAccessorFollowsLocaleState() {
        val original = AppLocaleState.currentLanguage
        try {
            AppLocaleState.currentLanguage = AppLanguage.ENGLISH
            assertEquals("Settings", S.settingsTitle)

            AppLocaleState.currentLanguage = AppLanguage.INDONESIAN
            assertEquals("Setelan", S.settingsTitle)

            AppLocaleState.currentLanguage = AppLanguage.ARABIC
            assertTrue(S.settingsTitle.isNotBlank())
        } finally {
            AppLocaleState.currentLanguage = original
        }
    }
}
