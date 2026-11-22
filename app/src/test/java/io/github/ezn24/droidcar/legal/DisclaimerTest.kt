package io.github.ezn24.droidcar.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerPolicyTest {
    @Test
    fun contentNotScrollableOnlyNeedsCountdown() {
        // The copy fits on one screen (no scrolling needed): only wait for the countdown
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = false, scrolledToBottom = false))
        assertTrue(canAcceptDisclaimer(countdownDone = true, contentScrollable = false, scrolledToBottom = false))
    }

    @Test
    fun scrollableContentRequiresBothCountdownAndBottom() {
        assertFalse(canAcceptDisclaimer(countdownDone = true, contentScrollable = true, scrolledToBottom = false))
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = true, scrolledToBottom = true))
        assertTrue(canAcceptDisclaimer(countdownDone = true, contentScrollable = true, scrolledToBottom = true))
    }

    @Test
    fun shorterElapsedButAtBottomStillBlocked() {
        // Scrolled to the bottom but did not wait the full 10 seconds: still not acceptable (the two conditions are parallel)
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = true, scrolledToBottom = true))
    }
}

class DisclaimerVersionTest {
    @Test
    fun firstRunNeedsDisclaimer() {
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 0, currentVersion = 1))
    }

    @Test
    fun acceptedCurrentVersionNeedsNothing() {
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 1))
        // An older version was accepted (1) but the current one is 2: the copy changed, must reconfirm
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 2))
        // A higher version already accepted (rolled back to an older APK): do not disturb
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 3, currentVersion = 2))
    }
}

class DisclaimerHintTest {
    @Test
    fun hintsExplainWhyAcceptIsBlocked() {
        // Neither met: the hint gives both the countdown and the scrolling requirement
        assertEquals(
            DisclaimerBlock.SCROLL_AND_WAIT,
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = true,
                scrolledToBottom = false,
            ),
        )
        // Countdown only
        assertEquals(
            DisclaimerBlock.WAIT,
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = false,
                scrolledToBottom = false,
            ),
        )
        // Scrolling only
        assertEquals(
            DisclaimerBlock.SCROLL,
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = false,
            ),
        )
        // Already acceptable: no hint
        assertNull(
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = true,
            ),
        )
    }
}

class DisclaimerContentTest {
    /** Reads the disclaimer body from strings.xml (the unit-test working directory = the app module). */
    private fun disclaimerText(folder: String): Map<String, String> {
        val xml = java.io.File("src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="(disclaimer_s\d_(?:title|body))">(.*?)</string>""")
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val locales = listOf("values", "values-b+zh+Hant", "values-en")

    @Test
    fun everyLocaleHasEverySection() {
        val keys = DisclaimerContent.sections.size * 2
        for (folder in locales) assertEquals(folder, keys, disclaimerText(folder).size)
    }

    @Test
    fun contentCoversMandatoryTopics() {
        // When shared it must make clear: test scope, driving risk, system permissions, unofficial status and trademarks
        val hans = disclaimerText("values").values.joinToString(" ")
        assertTrue(hans.contains("已经测试过的组合只有"))
        assertTrue(hans.contains("驾驶安全"))
        assertTrue(hans.contains("非官方"))
        assertTrue(hans.contains("商标"))
        for (folder in locales) {
            val text = disclaimerText(folder).values.joinToString(" ")
            assertTrue(folder, text.contains("Shizuku"))
            assertTrue(folder, text.contains("Apple Inc."))
        }
    }

    @Test
    fun contentHasNoMarkdownMarkers() {
        // The body goes straight into a TextView: `**`/`#` are shown literally, which is a formatting accident
        for (folder in locales) {
            val text = disclaimerText(folder).values.joinToString("")
            assertFalse(folder, text.contains("**"))
            assertFalse(folder, text.contains("##"))
            assertFalse(folder, text.contains("`"))
        }
    }
}
