package io.github.ezn24.cardeck.neo.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared thread pool for short-lived background tasks: one-off work from UI taps / session flow / polling goes through here,
 * replacing ad-hoc new Thread (thread-creation cost + unobservable + risk of runaway accumulation).
 *
 * A cached pool: idle threads are reclaimed after 60s, and a new one is created when none is idle; behavior is equivalent to new Thread,
 * but threads are reused at peaks and uniformly named. Long-running loop threads (GL composition / link reader / accept loops etc.)
 * do not go through here: they need their own names, interrupt, and lifecycle ownership.
 */
object Bg {
    private val counter = AtomicInteger(0)

    val pool: ExecutorService =
        Executors.newCachedThreadPool { r ->
            Thread(r, "app-bg-${counter.incrementAndGet()}").apply { isDaemon = true }
        }

    /** Submit a one-off background task. [name] goes into the thread name (locatable in logcat on block/crash). */
    fun run(
        name: String,
        block: () -> Unit,
    ) {
        pool.execute {
            Thread.currentThread().name = "app-bg-$name"
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.w("Bg", "bg task '$name' failed", t)
            }
        }
    }
}
