/*
 * Ported from FT (https://github.com/OneAboveAll1964/ft-app), Copyright 2026 OneAboveAll1964,
 * licensed under the Apache License, Version 2.0; see NOTICE. Modified for DroidCar.
 */
package io.github.ezn24.droidcar.iccoa

/** Bounded diagnostic metadata; exception messages may contain credentials or peer data. */
internal object IccoaTransportError {
    fun describe(error: Exception): String {
        val detail =
            when {
                error is java.net.SocketTimeoutException -> "timeout"
                error is java.io.EOFException -> "eof"
                error.message?.contains("connection reset", ignoreCase = true) == true -> "connection-reset"
                error.message?.contains("broken pipe", ignoreCase = true) == true -> "broken-pipe"
                error.message?.contains("socket closed", ignoreCase = true) == true -> "socket-closed"
                else -> "other"
            }
        val location =
            error.stackTrace.take(4).joinToString(",") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }
        return "exception=${error.javaClass.simpleName} detail=$detail at=$location"
    }
}
