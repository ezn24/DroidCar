package io.github.ezn24.cardeck.neo.legal

import android.content.Context

/**
 * Acceptance state and gating rules for the disclaimer.
 *
 * Recorded as a **version number** rather than a boolean: once shared, the text inevitably iterates (new tested car models, extra clauses),
 * and bumping [CURRENT_VERSION] by 1 makes existing users confirm again; a boolean would only re-prompt after uninstall/reinstall.
 */
object DisclaimerPrefs {
    /**
     * Current disclaimer version. **Bump by 1 whenever the disclaimer_content text changes**:
     * users accepted "that version" of the content, so changed terms must be re-confirmed.
     *
     * v2 (2026-10-18): app renamed CarDeck; added the "unofficial", "not MFi" and MIT open-source notes.
     */
    const val CURRENT_VERSION = 4

    private const val PREFS = "disclaimer"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    fun acceptedVersion(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ACCEPTED_VERSION, 0)

    fun hasAcceptedCurrent(context: Context): Boolean = acceptedVersion(context) >= CURRENT_VERSION

    fun accept(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCEPTED_VERSION, CURRENT_VERSION)
            .apply()
    }

    /** Test/diagnostic only: revoke acceptance (the prompt reappears at next launch). */
    fun reset(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACCEPTED_VERSION)
            .apply()
    }
}

/**
 * Availability condition of the "Accept" button (pure function, unit-testable).
 *
 * All three conditions must hold:
 * 1. Countdown finished ([countdownDone]), so the user has time to realize this is a safety-related notice;
 * 2. Content does not overflow ([contentScrollable]=false), or has been scrolled to the bottom ([scrolledToBottom]);
 *    long text must really expose its end, and "does the content overflow" cannot be used to dodge that;
 * 3. (Guaranteed by the caller) the user did not tap "Decline".
 *
 * Note: countdown and scrolling are **parallel** conditions, not serial; otherwise a user of long text
 * who scrolls for 30 seconds would wait another 10 seconds, a poor experience.
 */
internal fun canAcceptDisclaimer(
    countdownDone: Boolean,
    contentScrollable: Boolean,
    scrolledToBottom: Boolean,
): Boolean = countdownDone && (!contentScrollable || scrolledToBottom)

/**
 * Whether the launch-time mandatory disclaimer must be shown: this device has not accepted the **current version**.
 */
internal fun needsDisclaimerAtLaunch(
    acceptedVersion: Int,
    currentVersion: Int,
): Boolean = acceptedVersion < currentVersion

/** Reason "Accept" is not yet tappable (the UI picks the text per language). */
internal enum class DisclaimerBlock { SCROLL_AND_WAIT, WAIT, SCROLL }

/**
 * Bottom hint (tells the user "why the button does not respond"). Returns null when it can be tapped.
 */
internal fun disclaimerBlockReason(
    countdownDone: Boolean,
    contentScrollable: Boolean,
    scrolledToBottom: Boolean,
): DisclaimerBlock? =
    when {
        !countdownDone && contentScrollable && !scrolledToBottom -> DisclaimerBlock.SCROLL_AND_WAIT
        !countdownDone -> DisclaimerBlock.WAIT
        contentScrollable && !scrolledToBottom -> DisclaimerBlock.SCROLL
        else -> null
    }
