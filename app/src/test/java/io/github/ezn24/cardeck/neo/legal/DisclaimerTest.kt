package io.github.ezn24.cardeck.neo.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerPolicyTest {
    @Test
    fun contentNotScrollableOnlyNeedsCountdown() {
        // 文案一屏放得下（无需滚动）：只等倒计时
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
        // 滑到底了但没等够 10 秒：仍不可接受（两个条件是并列的）
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
        // 老版本接受过（1）但当前是 2：文案变了必须重新确认
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 2))
        // 已接受更高版本（回退安装旧 APK）：不再打扰
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 3, currentVersion = 2))
    }
}

class DisclaimerHintTest {
    @Test
    fun hintsExplainWhyAcceptIsBlocked() {
        // 两者都没满足：提示同时给出倒计时与滑动要求
        assertEquals(
            DisclaimerBlock.SCROLL_AND_WAIT,
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = true,
                scrolledToBottom = false,
            ),
        )
        // 只有倒计时
        assertEquals(
            DisclaimerBlock.WAIT,
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = false,
                scrolledToBottom = false,
            ),
        )
        // 只有滚动
        assertEquals(
            DisclaimerBlock.SCROLL,
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = false,
            ),
        )
        // 已可接受：无提示
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
    /** 读 strings.xml 里的免责声明正文（单测工作目录 = app 模块）。 */
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
        // 分享出去必须讲清：测试范围、驾驶风险、系统权限、非官方与商标
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
        // 正文直接进 TextView：`**`/`#` 会原样显示，属排版事故
        for (folder in locales) {
            val text = disclaimerText(folder).values.joinToString("")
            assertFalse(folder, text.contains("**"))
            assertFalse(folder, text.contains("##"))
            assertFalse(folder, text.contains("`"))
        }
    }
}
