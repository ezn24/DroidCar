package io.github.ezn24.cardeck.neo.util

import io.github.ezn24.cardeck.neo.util.SessionHooks.Extra
import io.github.ezn24.cardeck.neo.util.SessionHooks.ExtraType
import io.github.ezn24.cardeck.neo.util.SessionHooks.HookIntent
import io.github.ezn24.cardeck.neo.util.SessionHooks.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHooksTest {
    @Test
    fun encodeDecodeRoundTripKeepsEveryField() {
        val hooks =
            listOf(
                HookIntent(
                    target = Target.ACTIVITY,
                    action = "android.intent.action.VIEW",
                    packageName = "com.example.app",
                    className = ".Main",
                    data = "geo:0,0?q=home",
                    mimeType = "text/plain",
                    categories = listOf("android.intent.category.DEFAULT", "a.b"),
                    extras =
                        listOf(
                            Extra("name", ExtraType.STRING, "车机, \"引号\""),
                            Extra("n", ExtraType.INT, "3"),
                            Extra("on", ExtraType.BOOLEAN, "true"),
                        ),
                    enabled = false,
                ),
                HookIntent(action = "net.dinglisch.android.tasker.ACTION_TASK"),
            )
        assertEquals(hooks, SessionHooks.decode(SessionHooks.encode(hooks)))
    }

    @Test
    fun unknownEnumValuesFallBackToDefaults() {
        val decoded =
            SessionHooks.decode(
                """[{"target":"WHAT","action":"x","extras":[{"key":"k","type":"NOPE","value":"v"}]}]""",
            )
        assertEquals(Target.BROADCAST, decoded.single().target)
        assertEquals(
            ExtraType.STRING,
            decoded
                .single()
                .extras
                .single()
                .type,
        )
        assertEquals(true, decoded.single().enabled)
    }

    @Test
    fun summaryPrefersActionThenComponent() {
        assertEquals("a.B", HookIntent(action = "a.B", packageName = "p").summary())
        assertEquals("p/.C", HookIntent(packageName = "p", className = ".C").summary())
        assertEquals("p", HookIntent(packageName = "p").summary())
    }
}
