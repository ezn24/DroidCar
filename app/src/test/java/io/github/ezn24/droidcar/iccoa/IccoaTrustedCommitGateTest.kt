/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.iccoa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IccoaTrustedCommitGateTest {
    @Test fun confirmationAloneCannotSaveTheCredential() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onConfirmSent())
    }

    @Test fun authenticatedControlMustBeOpenedAfterAuthConfirm() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onConfirmSent())
        assertTrue(gate.onAuthenticatedControl())
        assertFalse(gate.onAuthenticatedControl())
        assertFalse(gate.onConfirmSent())
    }

    @Test fun controlCanRaceAheadOfConfirmationWithoutLosingCommit() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onAuthenticatedControl())
        assertTrue(gate.onConfirmSent())
        assertFalse(gate.onConfirmSent())
    }

    @Test fun cancellationBeforeBothSignalsCannotStoreAKey() {
        val gate = IccoaTrustedCommitGate()
        assertFalse(gate.onConfirmSent())
        gate.cancel()
        assertFalse(gate.onAuthenticatedControl())
        assertFalse(gate.onConfirmSent())
    }
}
