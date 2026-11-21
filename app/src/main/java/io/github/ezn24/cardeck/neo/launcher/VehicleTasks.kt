package io.github.ezn24.cardeck.neo.launcher

/** Both system dump headings must be supported; take only Task lines and ignore nested duplicates such as ActivityRecord. */
internal fun parseTaskDisplays(dump: String): Map<Int, Map<String, List<String>>> {
    val display = Regex("""(?:Display #(\d+)|Display: mDisplayId=(\d+))""")
    val task = Regex("""Task\{[^\x7D\r\n]*?#(\d+)[^\x7D\r\n]*?\bA=\d+:([A-Za-z0-9_.]+)(?=\s|:|\x7D)""")
    var id = -1
    val out = linkedMapOf<Int, MutableMap<String, MutableSet<String>>>()
    for (line in dump.lineSequence()) {
        display.find(line)?.let { id = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt() }
        // Accept only task entry lines ("* Task{"). The same dump also has **reference fields** such as mLastFocusedRootTask=Task{...},
        // mPreferredTopFocusableRootTask=Task{...} and task=Task{...} that keep stale values after the task is closed
        // - treating them as tasks makes "background apps" wrongly think a task cannot be closed (2026-11-17 Samsung voice recorder:
        // removeTask returned "No task with id" while the list still showed it).
        if (!line.trimStart().startsWith("* Task{")) continue
        val match = task.find(line) ?: continue
        if (id < 0) continue
        out.getOrPut(id) { linkedMapOf() }.getOrPut(match.groupValues[2]) { linkedSetOf() }.add(match.groupValues[1])
    }
    return out.mapValues { (_, apps) -> apps.mapValues { it.value.toList() } }
}

/** The phone main screen and CarDeck itself can never become a close target. */
internal fun vehicleTasks(
    snapshot: Map<Int, Map<String, List<String>>>,
    displays: Set<Int>,
    ownPackage: String,
): Map<String, List<String>> {
    val result = linkedMapOf<String, MutableSet<String>>()
    snapshot.filterKeys { it >= 0 && it in displays }.values.forEach { apps ->
        apps.filterKeys { it != ownPackage && !it.startsWith("$ownPackage.") }.forEach { (pkg, tasks) ->
            result.getOrPut(pkg) { linkedSetOf() }.addAll(tasks)
        }
    }
    return result.mapValues { it.value.toList() }
}

/** Prefer the exact previous task. An unrelated map opened on the phone is never a car task. */
internal fun findMapTask(
    snapshot: Map<Int, Map<String, List<String>>>,
    pkg: String,
    target: Int,
    vehicleDisplays: List<Int>,
    remembered: String?,
): Pair<String, Int>? {
    val rememberedId = remembered?.takeIf { it.startsWith("$pkg:") }?.substringAfter(':')
    if (rememberedId?.toIntOrNull() != null) {
        for ((display, apps) in snapshot) {
            if (rememberedId in apps[pkg].orEmpty()) return rememberedId to display
        }
    }
    for (display in listOf(target) + vehicleDisplays) {
        if (display <= 0) continue
        val id = snapshot[display]?.get(pkg)?.firstOrNull()
        if (id != null) return id to display
    }
    return null
}
