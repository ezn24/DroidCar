package io.github.ezn24.droidcar.audio

/**
 * Audio payload wrapping/timestamp parameters (**settled, a single set**).
 *
 * Background: on 2026-10-20 with `type=102` + AAC-LC 44100 on a Honda HFT / Alpine 8A250-30A (Telechips tcc805x
 * Android 10), the car's SETUP reply was complete, `/feedback` `sampleTime` advanced in real time at 44.1k and
 * the session stayed up, but the **speaker was silent**. Three possible wrapping differences
 * (raw frame / ADTS, declare spf / not, arrival timestamp / high-accuracy timestamp) were made switchable
 * and tested one by one in the car.
 *
 * Conclusion: the real cause was not wrapping; the AAC bitstream was being s16 byte-swapped like PCM before sending
 * (see `sealPacket` in [AudioSender]). With the swap fixed **scheme 1 plays**; the other two differences
 * made no difference on this car, so everything is reduced to one set and switching is gone.
 */
enum class AudioTuning(
    /** Whether to prepend an ADTS header to every frame. */
    val adts: Boolean,
    /** spf (sample frames per packet) declared in SETUP; null = not declared. */
    val spf: Int?,
    /** supportsHighAccuracyTimestamps in SETUP. */
    val highAccuracyTimestamps: Boolean,
) {
    /** Raw AAC frame + `spf=1024` + arrival timestamp: the only combination that worked on the real car. */
    RAW_SPF(
        adts = false,
        spf = 1024,
        highAccuracyTimestamps = false,
    ),
    ;

    /** One-line description for logs. */
    val detail: String
        get() = "raw AAC frame + spf=$spf + arrival timestamp"
}
