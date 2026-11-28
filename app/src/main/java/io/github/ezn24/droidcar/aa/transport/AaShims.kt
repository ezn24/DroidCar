package io.github.ezn24.droidcar.aa.transport

import io.github.ezn24.droidcar.SessionController

/** AARE's diagnostic log calls, mapped onto DroidCar's debug log. */
internal object DiagLog {
    fun add(text: String) = SessionController.appendLog("AA: $text")
}

/** AARE learns SSIDs to redact them from exported logs; DroidCar never logs the car's SSID in the first place. */
internal object DevLog {
    @Suppress("UNUSED_PARAMETER")
    fun learn(value: String) = Unit
}
