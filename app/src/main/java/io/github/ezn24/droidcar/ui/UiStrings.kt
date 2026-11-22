package io.github.ezn24.droidcar.ui

import android.content.Context

/** Non-Activity objects such as the settings menu also take text from the same Android locale resources. */
object UiStrings {
    private lateinit var app: Context

    fun initialize(context: Context) {
        app = context.applicationContext
    }

    fun text(id: Int): String = AppLanguage.localized(app).getString(id)

    fun format(
        id: Int,
        vararg args: Any,
    ): String = AppLanguage.localized(app).getString(id, *args)
}
