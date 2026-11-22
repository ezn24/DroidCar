package io.github.ezn24.cardeck.neo.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression protection for `cmd wifi connect-network` argument assembly.
 *
 * The head-unit hotspot name/password come from 0x5703 (sent by the head unit) and may contain spaces, Chinese, even single quotes;
 * a single mistake means "silent join fails and falls back to the specifier that shows a dialog", whose cause is hard to see from logs.
 * The boundaries are pinned here, also locking the security-type mapping (0=NONE 1=WEP 2=WPA2 3=transitional 4=WPA3).
 */
class WifiConnectCommandTest {
    @Test
    fun quotesSsidAndPassphraseWithSpaces() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW 12345' wpa2 'p@ss word' -d",
            WifiConnectCommand.build("DIRECT-BMW 12345", "p@ss word", 2),
        )
    }

    @Test
    fun escapesSingleQuotes() {
        // POSIX form: 'pa'\''ss' -- close the quote, escape the single quote, reopen the quote
        assertEquals(
            "cmd wifi connect-network 'a b' wpa2 'pa'\\''ss' -d",
            WifiConnectCommand.build("a b", "pa'ss", 2),
        )
    }

    @Test
    fun openNetworkHasNoPassphrase() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' open -d",
            WifiConnectCommand.build("DIRECT-BMW", "", 0),
        )
    }

    @Test
    fun declaredOpenWithPassphraseJoinsAsWpa2() {
        // Some head units declare security NONE but still send a real password
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa2 'secret123' -d",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 0),
        )
    }

    @Test
    fun emptyPassphraseFallsBackToOpen() {
        // Contradictory arguments (WPA2 given but no password): do not generate a half command, try as an open hotspot
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' open -d",
            WifiConnectCommand.build("DIRECT-BMW", "", 2),
        )
    }

    @Test
    fun wpa3OnlyUsesWpa3Keyword() {
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa3 'secret123' -d",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 4),
        )
    }

    @Test
    fun wpa3TransitionAndWepFallBackToWpa2() {
        // Transitional-mode AP also accepts WPA2; WEP has no keyword, likewise try wpa2 once
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa2 'secret123' -d",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 3),
        )
        assertEquals(
            "cmd wifi connect-network 'DIRECT-BMW' wpa2 'secret123' -d",
            WifiConnectCommand.build("DIRECT-BMW", "secret123", 1),
        )
    }

    @Test
    fun keepsNonAsciiSsidIntact() {
        assertEquals(
            "cmd wifi connect-network '车机 热点' wpa2 '密码 1234' -d",
            WifiConnectCommand.build("车机 热点", "密码 1234", 2),
        )
    }
}
