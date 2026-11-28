package io.github.ezn24.droidcar.iccoa

import io.github.ezn24.droidcar.SessionController

/**
 * The few FT types the ported ICCOA code refers to, mapped onto DroidCar: FT's DiagLog writes into DroidCar's
 * debug log with an `ICCOA:` prefix, CarTouch is the head-unit touch FT routes, and the vehicle profile id is
 * FT's single legacy profile (DroidCar keeps one ICCOA identity per phone).
 */
internal object DiagLog {
    fun i(
        tag: String,
        text: String,
    ) = SessionController.appendLog("$tag: $text")

    fun w(
        tag: String,
        text: String,
    ) = SessionController.appendLog("$tag: WARN $text")

    fun d(
        tag: String,
        text: String,
    ) = SessionController.appendLog("$tag: $text")

    fun e(
        tag: String,
        text: String,
        t: Throwable? = null,
    ) = SessionController.appendLog("$tag: ERROR $text${t?.let { " - ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")
}

data class CarTouch(
    val action: Int,
    val x: Int,
    val y: Int,
    val x2: Int = -1,
    val y2: Int = -1,
    val index: Int = 0,
) {
    companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }
}

internal object IccoaProfile {
    const val ID = "legacy"
}
