package io.github.ezn24.cardeck.neo.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuGuidanceTest {
    @Test
    fun notRunningTellsUserToStartService() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.NOT_RUNNING)
        assertTrue(guidance.title.contains("未运行"))
        // An actionable step must be given (open the Shizuku app to start it), otherwise the user has no way to begin
        assertEquals("打开 Shizuku", guidance.primaryLabel)
        assertTrue(guidance.message.contains("启动"))
    }

    @Test
    fun unauthorizedTellsUserToGrant() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.UNAUTHORIZED)
        assertTrue(guidance.title.contains("未授权"))
        assertEquals("请求授权", guidance.primaryLabel)
        assertTrue(guidance.message.contains("允许"))
    }

    @Test
    fun notInstalledHasNoFakeAction() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.NOT_INSTALLED)
        assertTrue(guidance.title.contains("安装"))
        // When not installed, no made-up jump/download button should be given
        assertNull(guidance.primaryLabel)
        assertTrue(guidance.message.contains("Shizuku"))
    }

    @Test
    fun eachStateExplainsConsequenceOthersDoNotAllLookSame() {
        // The three-state copy must differ from each other: merged into one "Shizuku not ready" the user cannot tell whether to install/start/authorize
        val titles =
            listOf(
                ShizukuGate.State.NOT_INSTALLED,
                ShizukuGate.State.NOT_RUNNING,
                ShizukuGate.State.UNAUTHORIZED,
            ).map { ShizukuGate.guidanceOf(it).title }
        assertEquals(titles.size, titles.toSet().size)
        // All three not-ready states must state the consequence "connection will fail" (otherwise the user thinks it can connect)
        listOf(
            ShizukuGate.State.NOT_RUNNING,
            ShizukuGate.State.UNAUTHORIZED,
        ).forEach {
            assertTrue(ShizukuGate.guidanceOf(it).message.contains("失败"))
        }
    }

    @Test
    fun readyHasGuidanceObject() {
        val guidance = ShizukuGate.guidanceOf(ShizukuGate.State.READY)
        assertNotNull(guidance)
        assertNull(guidance.primaryLabel)
    }
}
