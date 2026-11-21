package io.github.ezn24.cardeck.neo.render

/** Android-independent time curve; reversing midway starts from the current value, avoiding jumps on fast display switching. */
internal data class LayoutMotion(
    val from: Float,
    val to: Float,
    val startedAt: Long,
) {
    fun valueAt(now: Long): Float = from + (to - from) * progress(now - startedAt)

    fun isRunning(now: Long): Boolean = from != to && now - startedAt < DURATION_MS

    companion object {
        const val DURATION_MS = 320L

        fun progress(elapsed: Long): Float {
            val t = (elapsed.toFloat() / DURATION_MS).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
