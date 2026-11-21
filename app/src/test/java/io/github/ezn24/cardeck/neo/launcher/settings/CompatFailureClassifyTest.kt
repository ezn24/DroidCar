package io.github.ezn24.cardeck.neo.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `am compat` failure classification test.
 *
 * This classification decides "whether the preference is persisted": persisting a permanent rejection (user build restriction) only makes the next connection
 * waste an applyAll round, with the panel showing "enabled" while the system actually has no effect; a transient failure is the opposite, the
 * user's intent should be kept so it can be retried. Measured samples come from real output of a Redmi Android 13 user build on 2026-10-18.
 */
class CompatFailureClassifyTest {
    /** An installed ordinary third-party app rejected on a user build (the real output of this issue). */
    @Test
    fun nonDebuggableOnUserBuildIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Cannot override a change on a non-debuggable app and user build."),
        )
    }

    /** Another rejection wording when the target is the default object of that ChangeId. */
    @Test
    fun defaultChangeOnUserBuildIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Cannot override a default enabled/disabled change on a user build."),
        )
    }

    /**
     * **This ROM has not registered the ChangeId** (Redmi Android 13 measured output).
     *
     * This is the real reason for "Honor can change it, Redmi cannot": these two ChangeIds are OEM-customized items,
     * Honor registered them (392 in total), Redmi did not (173). When unregistered, `am compat` first emits an
     * `is not known yet` warning, then rejects with the user-build wording.
     */
    @Test
    fun unregisteredChangeIdIsUnsupported() {
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure(
                "Warning! Change 265451093 is not known yet. Enabling/disabling it could have no effect.\n" +
                    "Cannot override a change on a non-debuggable app and user build.",
            ),
        )
        // A warning-only line (some ROMs reply with only the warning) is also judged permanent
        assertEquals(
            AppOrientationCompat.WriteResult.UNSUPPORTED,
            classifyCompatFailure("Warning! Change 265464455 is not known yet."),
        )
    }

    /** Shizuku not connected: transient failure, retryable. */
    @Test
    fun shizukuNotConnectedIsTemporary() {
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Shizuku 未连接"),
        )
    }

    /** Timeout/process killed: transient failure. */
    @Test
    fun emptyOrTimeoutIsTemporary() {
        assertEquals(AppOrientationCompat.WriteResult.FAILED, classifyCompatFailure(""))
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("命令执行失败：timeout"),
        )
    }

    /** Successful output must not be judged a permanent rejection (defensive: the caller classifies only on failure). */
    @Test
    fun successTextIsNotPermanent() {
        assertEquals(
            AppOrientationCompat.WriteResult.FAILED,
            classifyCompatFailure("Enabled change 265451093 for com.example.app."),
        )
    }

    /**
     * Version threshold: these two ChangeIds only entered AOSP (`android.content.pm.ActivityInfo`) from **Android 14 / SDK 34**.
     * Android 13 and earlier ActivityInfo has
     * not a single orientation ChangeId (measured A13=0, A14=9), so lower versions
     * can be judged unsupported without running dumpsys.
     *
     * Measured comparison: Redmi K40 (SDK 33) unsupported; Honor Magic7 Pro (SDK 37) supported.
     */
    @Test
    fun minSupportedSdkIsAndroid14() {
        assertEquals(34, AppOrientationCompat.MIN_SUPPORTED_SDK)
    }
}
