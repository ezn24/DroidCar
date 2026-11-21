package io.github.ezn24.cardeck.neo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sort rules of the audio profile memory ([AudioProfileMemory.order]).
 *
 * Background (2026-10-19 Honda HFT / Alpine measured): after choosing "video + audio" the session establishes and video is fine,
 * but about 6s after audio starts the head unit tears down the whole AirPlay session; video only is stable. The head unit does not say which profile of
 * audio is incompatible, so it can only be inferred from "survival time after streaming starts", and the conclusion is persisted per head unit so the next connection switches profile.
 */
class AudioProfileMemoryTest {
    // This head unit's real declaration: the media profile only has 44.1k, the compatibility profile has both 48k/44.1k
    private val hondaCandidates =
        listOf(
            AudioProfile("media", 1L shl 11, 44_100, 250),
            AudioProfile("compatibility", 1L shl 15, 48_000, 250),
            AudioProfile("compatibility", 1L shl 11, 44_100, 250),
        )

    @Test
    fun emptyMemoryKeepsBaselineOrder() {
        assertEquals(hondaCandidates, AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry()))
    }

    @Test
    fun workingProfileGoesFirst() {
        val compat48 = hondaCandidates[1].key
        val ordered = AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(working = compat48))
        assertEquals(compat48, ordered.first().key)
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun rejectedProfileGoesLastButStaysAsFallback() {
        val media44 = hondaCandidates[0].key
        val ordered =
            AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(rejected = setOf(media44)))
        assertEquals(media44, ordered.last().key)
        // Still kept among candidates: it may work after a firmware change
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun allRejectedKeepsTryingInsteadOfGoingSilent() {
        val all = hondaCandidates.map { it.key }.toSet()
        val ordered = AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.Entry(rejected = all))
        // All judged dead contradicts the memory (swapped head unit/firmware upgrade reusing the same identity key) -- try everything as usual, avoiding permanent silence
        assertEquals(hondaCandidates.size, ordered.size)
    }

    @Test
    fun disabledMeansNoAudioAtAll() {
        assertTrue(
            AudioProfileMemory
                .order(hondaCandidates, AudioProfileMemory.Entry(disabled = true))
                .isEmpty(),
        )
        assertFalse(AudioProfileMemory.order(emptyList(), AudioProfileMemory.Entry()).isNotEmpty())
    }

    @Test
    fun profileKeyIgnoresLatencySoRelearningWorks() {
        val a = AudioProfile("media", 1L shl 11, 44_100, 250)
        val b = AudioProfile("media", 1L shl 11, 44_100, 400)
        assertEquals(a.key, b.key)
        // Case differences (Media/media have appeared in /info) must not create two memories;
        // the key carries both stream type and codec: the same format on 102/AAC and 100/PCM are entirely different paths
        assertEquals("100|media|2048|PCM", AudioProfile("Media", 1L shl 11, 44_100, 250).key)
        assertEquals(
            "102|media|4194304|AAC_LC",
            AudioProfile(
                "media",
                1L shl 22,
                44_100,
                250,
                streamType = 102L,
                codec = AudioCodec.AAC_LC,
            ).key,
        )
    }

    @Test
    fun receiverKeyIsStableAcrossCasingAndPunctuation() {
        val a = AudioProfileMemory.receiverKey("Alpine Electronics (China) Co., Ltd. 8A250-30A")
        val b = AudioProfileMemory.receiverKey("alpine electronics china co ltd 8a250 30a")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("[a-z0-9_]+")))
        assertFalse(a == AudioProfileMemory.receiverKey("AUDI 3260 DELPHI"))
    }

    @Test
    fun setupFailureAloneNeverDisablesAudio() {
        // A rejected/timed-out SETUP may just be head-unit resource arbitration or network jitter: only demote the ordering, do not declare the whole head unit dead
        val id = "test receive setup-fail"
        AudioProfileMemory.recordRejected(null, id, "media|2048", setOf("media|2048"), markExhausted = false)
        val entry = AudioProfileMemory.load(null, id)
        assertTrue(entry.rejected.contains("media|2048"))
        assertFalse(entry.disabled)
    }

    @Test
    fun teardownOfEveryProfileDisablesAudio() {
        // Only deterministic evidence like "the whole link was torn down after streaming starts" may declare a head unit's audio dead
        val id = "test receive teardown-all"
        val all = setOf("media|2048", "compatibility|32768")
        AudioProfileMemory.recordRejected(null, id, "media|2048", all)
        AudioProfileMemory.recordRejected(null, id, "compatibility|32768", all)
        assertTrue(AudioProfileMemory.load(null, id).disabled)
        assertTrue(AudioProfileMemory.order(hondaCandidates, AudioProfileMemory.load(null, id)).isEmpty())
    }
}
