package io.github.ezn24.cardeck.neo.audio

import android.content.Context

/**
 * **Per-head-unit persisted memory** of audio profiles (same idea as
 * [io.github.ezn24.cardeck.neo.iap2.MfiLengthMemory]).
 *
 * Background (2026-10-19, Honda HFT / Alpine, real car): with "video+audio" the session came up,
 * video was fine, audio SETUP returned 200 with a dataPort, but ~6s after audio packets started
 * the car tore down the whole AirPlay session (`EVENT: read ended - Connection reset`). Video-only
 * on the same car is stable. So a **specific audio profile** is the trap, and which one differs
 * per car (Audi media/44.1k works, this car declares only media/44.1k and it fails). Hard-coding
 * a profile or blind-trying on every connection are both wrong, so remember three things per car:
 *
 *  - [Entry.working]: the last profile that **survived the liveness window** -> first choice next time;
 *  - [Entry.rejected]: profiles torn down quickly after going live -> sorted last;
 *  - [Entry.disabled]: every profile has been torn down -> stop trying audio on this car and push
 *    video only (silence beats "connects then drops").
 *
 * The "quick" window is supplied by the caller ([SessionController] uses 20s).
 */
object AudioProfileMemory {
    /** Audio memory of a single head unit. */
    data class Entry(
        val working: String? = null,
        val rejected: Set<String> = emptySet(),
        val disabled: Boolean = false,
    ) {
        val isEmpty: Boolean get() = working == null && rejected.isEmpty() && !disabled
    }

    // v2: profile key now includes "stream type + encoding" (102/AAC-LC and 100/PCM are entirely different paths).
    // A new file name instead of reusing the old one keeps the v1-era verdict "all three PCM profiles
    // tear down the link" from blocking us: it only covered PCM and says nothing about the new AAC path.
    private const val FILE = "audio_profile_memory_v2"
    private const val KEY_OK = "ok_"
    private const val KEY_REJECTED = "rejected_"
    private const val KEY_DISABLED = "disabled_"

    /**
     * Head-unit identity -> storage key (stable characters only, so variable parts of BT names/firmware strings do not
     * affect matching).
     */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        return Entry(
            working = prefs.getString(KEY_OK + key, null)?.takeIf { it.isNotBlank() },
            rejected = readKeys(prefs.getString(KEY_REJECTED + key, null)),
            disabled = prefs.getBoolean(KEY_DISABLED + key, false),
        )
    }

    /**
     * The profile survived the liveness window: record it as preferred and refute any historical rejected verdict
     * (firmware upgrades may fix it).
     */
    fun recordWorking(
        context: Context?,
        identityText: String,
        profileKey: String,
    ) {
        update(context, identityText) { entry ->
            Entry(
                working = profileKey,
                rejected = entry.rejected - profileKey,
                disabled = false,
            )
        }
    }

    /**
     * The profile is unusable: sort it last.
     *
     * @param markExhausted whether to set [Entry.disabled] directly when every profile is ruled out. **Only
     *   deterministic evidence (link teardown) may set it**; a SETUP rejection/timeout may be network or
     *   arbitration jitter, so pass false.
     */
    fun recordRejected(
        context: Context?,
        identityText: String,
        profileKey: String,
        allCandidates: Set<String>,
        markExhausted: Boolean = true,
    ) {
        update(context, identityText) { entry ->
            if (entry.working == profileKey) {
                entry
            } else {
                val rejected = entry.rejected + profileKey
                val exhausted =
                    markExhausted && allCandidates.isNotEmpty() && allCandidates.all { it in rejected }
                Entry(working = entry.working, rejected = rejected, disabled = exhausted)
            }
        }
    }

    /** Manual reset (after switching head units, or to re-probe after a firmware upgrade). */
    fun reset(
        context: Context?,
        identityText: String,
    ) {
        update(context, identityText) { Entry() }
    }

    /** Whether any memory exists (decides whether the UI shows the "clear audio compatibility memory" entry). */
    fun hasAny(context: Context?): Boolean {
        val all = prefs(context)?.all ?: return synchronized(memory) { memory.isNotEmpty() }
        return all.any { (key, value) ->
            when {
                key.startsWith(KEY_DISABLED) -> value == true
                key.startsWith(KEY_OK) -> (value as? String)?.isNotBlank() == true
                key.startsWith(KEY_REJECTED) -> (value as? String)?.isNotBlank() == true
                else -> false
            }
        }
    }

    /** Clears audio memory for all head units (firmware upgrade or new car, re-probe). */
    fun clearAll(context: Context?) {
        synchronized(memory) {
            memory.clear()
            prefs(context)?.edit()?.clear()?.apply()
        }
    }

    /** For printing: compress to a one-line log. */
    fun describe(entry: Entry): String =
        when {
            entry.disabled -> "audio judged incompatible on this head unit (video only)"
            entry.isEmpty -> "none"
            else ->
                buildList {
                    entry.working?.let { add("working=$it") }
                    if (entry.rejected.isNotEmpty()) add("torn-down=${entry.rejected.sorted().joinToString("/")}")
                }.joinToString(", ")
        }

    /**
     * Computes this round's attempt order (pure function, easy to unit test).
     *
     * @param candidates baseline candidates sorted most-recommended first (from the head unit's /info).
     * @param entry the head unit's persisted memory.
     * @return profiles to try in order; **an empty list means do not try audio again** (memory says every profile tears down the link).
     */
    fun order(
        candidates: List<AudioProfile>,
        entry: Entry,
    ): List<AudioProfile> {
        if (candidates.isEmpty()) return emptyList()
        if (entry.disabled) return emptyList()
        val keys = candidates.map { it.key }.toSet()
        val rejected = entry.rejected.filter { it in keys }.toSet()
        val working = candidates.firstOrNull { it.key == entry.working }
        val rest = candidates.filter { it.key != working?.key }
        // Only push rejected to the end while some profile is not yet ruled out; if all are ruled out the memory contradicts
        // reality (switched car/firmware upgrade but same identity key), so try everything as usual to avoid permanent silence.
        val allRejected = rest.isNotEmpty() && rest.all { it.key in rejected }
        val safe = if (allRejected) rest else rest.filter { it.key !in rejected }
        val risky = if (allRejected) emptyList() else rest.filter { it.key in rejected }
        return (listOfNotNull(working) + safe + risky).distinctBy { it.key }
    }

    // ---- internals ----

    private val memory = HashMap<String, Entry>()

    private fun prefs(context: Context?): android.content.SharedPreferences? = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun update(
        context: Context?,
        identityText: String,
        transform: (Entry) -> Entry,
    ) {
        val key = receiverKey(identityText)
        synchronized(memory) {
            val next = transform(load(context, identityText))
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit()
                .apply {
                    if (next.working != null) putString(KEY_OK + key, next.working) else remove(KEY_OK + key)
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                    putBoolean(KEY_DISABLED + key, next.disabled)
                }.apply()
        }
    }

    private fun readKeys(raw: String?): Set<String> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
}
