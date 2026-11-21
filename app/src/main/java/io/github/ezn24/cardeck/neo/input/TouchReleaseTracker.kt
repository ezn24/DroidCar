package io.github.ezn24.cardeck.neo.input

import io.github.ezn24.cardeck.neo.input.TouchReports.Contact

/** An empty slot and a zeroed release report look identical; tell them apart by whether the slot has a valid press. */
internal class TouchReleaseTracker {
    private val active = mutableMapOf<Int, Contact>()

    fun accept(report: Contact): Contact? {
        if (report.down) {
            active[report.slot] = report
            return report
        }
        val previous = active.remove(report.slot) ?: return null
        return if (report.xPixels == 0 && report.yPixels == 0) previous.copy(down = false) else report
    }

    fun reset() {
        active.clear()
    }
}
