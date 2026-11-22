package io.github.ezn24.droidcar.notifications

internal fun shouldShowNotificationBanner(
    self: Boolean,
    ongoing: Boolean,
    summary: Boolean,
    media: Boolean,
    hasText: Boolean,
): Boolean = !self && !ongoing && !summary && !media && hasText
