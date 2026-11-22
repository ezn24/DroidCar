package io.github.ezn24.droidcar.shizuku

/**
 * Pure decisions of the VirtualDeviceManager route (no Android dependency, unit-tested). See [VirtualDeviceSession].
 */
object VirtualDeviceRoute {
    /** VirtualDeviceManager / VirtualDeviceParams are public from Android 14 (SDK 34). */
    const val MIN_SDK = 34

    const val FLAG_PUBLIC = 1
    const val FLAG_PRESENTATION = 1 shl 1
    const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val FLAG_TRUSTED = 1 shl 10
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12

    private const val BASE =
        FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL

    /**
     * Trusted displays, tried in order: with PUBLIC first (a device display is private by default, and private displays
     * refuse third-party IME windows), then without it (the value upstream's reference uses, 1354).
     */
    val trustedFlagCandidates: List<Int> = listOf(BASE or FLAG_TRUSTED or FLAG_PUBLIC, BASE or FLAG_TRUSTED)

    /**
     * Dock: trusted first so it joins the virtual device's display group. Upstream measured on Honor that a device
     * display outside the group stops rendering once the phone locks (dock bar frozen black). Fallback: untrusted with
     * an explicit lock exemption and its own group.
     */
    val dockFlagCandidates: List<Int> =
        listOf(BASE or FLAG_TRUSTED, BASE or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED)

    /** Whether to try the route: SDK 34+, a known car Bluetooth address, and not already failed this session. */
    fun shouldAttempt(
        sdkInt: Int,
        companionMac: String?,
        failedThisSession: Boolean,
    ): Boolean = sdkInt >= MIN_SDK && !companionMac.isNullOrBlank() && !failedThisSession

    /** `cmd role get-role-holders` output lists holders by line or, on the Samsung S24, separated by semicolons; compare whole tokens (prefix packages exist). */
    fun roleHoldersContain(
        output: String,
        packageName: String,
    ): Boolean = output.split(Regex("[\\s;]+")).contains(packageName)
}
