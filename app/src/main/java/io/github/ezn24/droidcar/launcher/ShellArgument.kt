package io.github.ezn24.droidcar.launcher

/** Pass component names literally through sh -c, including the dollar sign in nested activities. */
internal fun String.asShellArgument(): String = "'" + replace("'", "'\"'\"'") + "'"
