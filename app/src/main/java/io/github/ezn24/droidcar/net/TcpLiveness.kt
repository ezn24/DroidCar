package io.github.ezn24.droidcar.net

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.Socket

/**
 * TCP peer health check for long-lived CarPlay connections (follows DiPlay `TcpLiveness`, 2026-10-21 version).
 *
 * Why it must be set explicitly: the Android kernel default keepalive only starts probing after **two hours**. When the car is
 * switched off / the phone leaves hotspot range the link often **sends no RST** (WiFi just vanishes), so the control channel reader
 * hangs in `read()` until a system timeout: frozen picture, watchdog never fires, the user must disconnect and reconnect by hand.
 *
 * Values match DiPlay: start probing after 10s idle, every 3s, 3 unanswered probes declare the peer dead
 * (~19s total), and `TCP_USER_TIMEOUT` caps "sent but unacknowledged data" at 20s.
 * 19s is deliberately above the head unit's 15s AirPlay watchdog so we never declare a healthy session dead before the head unit does.
 *
 * **API difference (hit before)**: before API 29 `ParcelFileDescriptor.fromSocket` returns a wrapper around the
 * socket's **own** fd, and close/finalize closes the original socket too. So it is only used for a dup on 29+;
 * 26~28 reflect `Socket.getFileDescriptor$()` and set options on the original fd (light greylist on
 * Android 9, callable at any targetSdk); API 25 and below have no public
 * `setsockoptInt`, so we only log a line.
 */
object TcpLiveness {
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6

    /** Same defaults as DiPlay (seconds / count / milliseconds). */
    private const val KEEP_IDLE_SECONDS = 10
    private const val KEEP_INTERVAL_SECONDS = 3
    private const val KEEP_COUNT = 3
    private const val USER_TIMEOUT_MILLIS = 20_000

    /**
     * Enable and tune keepalive on a long-lived socket. Failures are only logged, never thrown: the head unit may not need it,
     * but a failed setting must never block the connection.
     */
    fun configure(
        socket: Socket,
        label: String,
        log: (String) -> Unit,
    ) {
        runCatching { socket.keepAlive = true }
        if (Build.VERSION.SDK_INT < 26) {
            log("TCP: $label cannot tune keepalive (API ${Build.VERSION.SDK_INT} < 26)")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ParcelFileDescriptor.fromSocket(socket).use { duplicate ->
                    tune(duplicate.fileDescriptor)
                }
            } else {
                val fd =
                    socket.javaClass
                        .getMethod("getFileDescriptor\$")
                        .invoke(socket) as FileDescriptor
                tune(fd)
            }
            log(
                "TCP: $label keepalive enabled" +
                    " (idle=${KEEP_IDLE_SECONDS}s interval=${KEEP_INTERVAL_SECONDS}s count=$KEEP_COUNT" +
                    " userTimeout=${USER_TIMEOUT_MILLIS}ms)",
            )
        } catch (error: Exception) {
            log("TCP: $label keepalive tuning failed (${error.javaClass.simpleName}: ${error.message})")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun tune(fd: FileDescriptor) {
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, KEEP_IDLE_SECONDS)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, KEEP_INTERVAL_SECONDS)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, KEEP_COUNT)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
    }
}
