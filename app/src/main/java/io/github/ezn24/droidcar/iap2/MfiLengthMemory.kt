package io.github.ezn24.droidcar.iap2

import android.content.Context

/**
 * **Per-head-unit persistent memory** of the MFi challenge length.
 *
 * Why (Audi MMI 3315 measured, 2026-10-19): a 20B challenge gets silence for 6 seconds on this head unit, while 32B
 * gets a signature in 0.64s, so every connection wasted 6s. Receivers also prefer opposite lengths:
 *  - DiPlay (Carlinkit AuthV3): accepts only **32B**, any other length drops the link;
 *  - Audi MMI 6318: answers 20B at once, 32B once dropped the link after 0.4s;
 *  - Audi MMI 3315: 20B silent, only **32B** is answered.
 * So one length cannot be hard-coded globally, and in-process memory is not enough (lost on app restart, paying
 * the 6s again on every reconnect). We persist three things per "head unit identity (model + manufacturer)":
 *
 *  - [Entry.succeeded]: the length that most recently **got a signature** -> tried first next time, no probing;
 *  - [Entry.silent]: lengths with no answer within the window -> ordered later (still kept as a fallback, firmware may change);
 *  - [Entry.rejected]: lengths explicitly rejected with 0xAA04 or that **caused a link drop** -> ordered last.
 *
 * Ordering rules are in [orderAttempts] (pure function, unit-testable): the succeeded length is always first; the rest
 * follow the caller's baseline order (real cars 20B first / test bench 32B first), then silent and
 * rejected last. If every length in the baseline was judged rejected, all are tried as usual to avoid a permanent deadlock.
 */
object MfiLengthMemory {
    /** MFi memory of a single head unit. */
    data class Entry(
        val succeeded: Int? = null,
        val silent: Set<Int> = emptySet(),
        val rejected: Set<Int> = emptySet(),
    ) {
        val isEmpty: Boolean get() = succeeded == null && silent.isEmpty() && rejected.isEmpty()
    }

    private const val FILE = "mfi_length_memory"
    private const val KEY_OK = "ok_"
    private const val KEY_SILENT = "silent_"
    private const val KEY_REJECTED = "rejected_"

    /**
     * Head unit identity -> storage key. Only stable fields are used so a variable suffix in the Bluetooth name does not break
     * matching.
     */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    /** Read the memory of a head unit (without a context it falls back to an in-process table, for unit tests). */
    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        val ok = prefs.getInt(KEY_OK + key, 0).takeIf { it > 0 }
        return Entry(
            succeeded = ok,
            silent = readLengths(prefs.getString(KEY_SILENT + key, null)),
            rejected = readLengths(prefs.getString(KEY_REJECTED + key, null)),
        )
    }

    fun recordSuccess(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            // A success disproves the old silent/rejected verdict of this length (behavior may change after a firmware upgrade)
            Entry(succeeded = length, silent = entry.silent - length, rejected = entry.rejected - length)
        }
    }

    fun recordSilent(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(silent = entry.silent + length, rejected = entry.rejected - length)
            }
        }
    }

    fun recordRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(rejected = entry.rejected + length, silent = entry.silent - length)
            }
        }
    }

    /**
     * Hard rejection: the peer dropped the link during a challenge of this length (distinct from an 0xAA04 reject word / silence).
     * This is strong evidence and must overturn even a recorded "success" of the same length: MFi replies have an attribution
     * ambiguity (a late reply arriving after the previous length's window gets credited to the current length; DiPlay
     * 2026-10-24: a late 32B reply was recorded as a 20B success, after which every round dropped the link at 20B and stayed locked).
     */
    fun recordHardRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            val succeeded = if (entry.succeeded == length) null else entry.succeeded
            Entry(
                succeeded = succeeded,
                silent = (entry.silent - length),
                rejected = entry.rejected + length,
            )
        }
    }

    /** For printing: compress the memory into one log line (to compare with measurements). */
    fun describe(entry: Entry): String =
        if (entry.isEmpty) {
            "none"
        } else {
            buildList {
                entry.succeeded?.let { add("succeeded=${it}B") }
                if (entry.silent.isNotEmpty()) add("silent=${entry.silent.sorted().joinToString("/")}B")
                if (entry.rejected.isNotEmpty()) add("rejected=${entry.rejected.sorted().joinToString("/")}B")
            }.joinToString(", ")
        }

    /**
     * Compute the attempt order for this round (pure function).
     *
     * @param baseline baseline order (list of lengths) given by the caller according to the receiver type.
     * @param entry persistent memory of this head unit.
     * @return lengths to try in order; guaranteed non-empty and covering every length in baseline.
     */
    fun orderAttempts(
        baseline: List<Int>,
        entry: Entry,
    ): List<Int> {
        // "Succeeded but also hard-rejected" = contradictory memory (late reply misattributed), so it cannot keep first place:
        // demote it to a normal length that sorts before the rejected ones
        val succeeded = entry.succeeded?.takeIf { it !in entry.rejected }
        val rest = baseline.filter { it != succeeded }
        // Only push rejected to the end when "some length is not yet ruled out";
        // all rejected contradicts the memory (firmware changed / different car with the same identity), so try everything as usual.
        val allRejected = rest.isNotEmpty() && rest.all { it in entry.rejected }
        val rejected = if (allRejected) emptySet() else entry.rejected.filter { it in baseline }.toSet()
        val silent = entry.silent.filter { it in rest && it !in rejected }.toSet()
        val normal = rest.filter { it !in silent && it !in rejected }
        val silentOrdered = rest.filter { it in silent }
        val rejectedOrdered = rest.filter { it in rejected }
        return (listOfNotNull(succeeded) + normal + silentOrdered + rejectedOrdered).distinct()
    }

    // ---- Internals ----

    private val memory = HashMap<String, Entry>()

    private fun prefs(context: Context?): android.content.SharedPreferences? = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun update(
        context: Context?,
        identityText: String,
        transform: (Entry) -> Entry,
    ) {
        val key = receiverKey(identityText)
        synchronized(memory) {
            val current = load(context, identityText)
            val next = transform(current)
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit()
                .apply {
                    if (next.succeeded != null) putInt(KEY_OK + key, next.succeeded) else remove(KEY_OK + key)
                    putString(KEY_SILENT + key, next.silent.sorted().joinToString(","))
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                }.apply()
        }
    }

    private fun readLengths(raw: String?): Set<Int> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.toSet()
            .orEmpty()
}
