package io.github.ezn24.cardeck.neo.notifications

internal fun shouldShowNotificationBanner(
    self: Boolean,
    ongoing: Boolean,
    summary: Boolean,
    media: Boolean,
    hasText: Boolean,
): Boolean = !self && !ongoing && !summary && !media && hasText
