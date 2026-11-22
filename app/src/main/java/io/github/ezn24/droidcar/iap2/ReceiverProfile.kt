package io.github.ezn24.droidcar.iap2

import io.github.ezn24.droidcar.net.AirPlayFeatures

/**
 * **Head unit profile**: gathers scattered version/capability signals into one explainable verdict.
 *
 * Signals come from three places at different times: the 0x1D01 identity frame (early Bluetooth bring-up), mDNS//info
 * features and srcvers (endpoint resolution), and real-car behavior (challenge length / link-drop patterns, learned by the
 * existing Memory classes).
 * It can be recomputed whenever a new signal arrives; [classify] is a pure function, same input always gives the same output.
 *
 * Two iron rules (generalizing the HID descriptor lesson):
 *  1. **Branch on capability, never hard-code version numbers/brands**: srcvers/features are only priors,
 *     real dispatch follows capability lists (receive/send lists, audioFormats, hidDescriptors) and measured behavior;
 *  2. **Verdicts must be explainable**: [describe] writes the evidence into the log so a wrong verdict shows which signal misled.
 */
object ReceiverProfile {
    /** Three eras (follows the catplay classification, §9f). UNKNOWN = signal missing, keep the old path and do not be aggressive. */
    enum class Era { PRE_HOMEKIT, OPTIONAL_PAIRING, MODERN, UNKNOWN }

    /** WiFi credential exchange protocol generation (basis for the PR #10 dispatch, now folded into the profile). */
    enum class WifiGen { LEGACY, NEW, UNKNOWN }

    /**
     * iAP2 virtual link generation (decided 2026-10-23, pure function).
     *
     * **Second ruling 2026-10-23: the generation no longer dispatches marker behavior.** Every head unit must complete link
     * negotiation (marker = the head unit is waiting for our session table, resent endlessly at 1Hz; without a reply the link never reaches
     * NORMAL and is torn down ~10s after RECORD: 0.1.9 measured 10.17s, 0.1.17 owner-measured 10.18s).
     * We once ignored marker for CLASSIC_MIB3 (0.1.16/0.1.17); its premise, "the Audi proceeded normally when 0.1.9 ignored marker",
     * was refuted by 0.1.9's own log. The real cause of "negotiation hangs the head unit UI" is the missing modesChanged resource
     * re-assertion after negotiation completes (AADisplay r17 timeline). The verdict is kept for logs and future
     * dispatch (e.g. MFi challenge length prior, in-tunnel message differences).
     *
     * Criteria (all from protocol self-reports, no new dependency beyond brand guessing):
     * DELPHI manufacturer + AUDI model + sparse lists (fewer than 10 items combined, measured 2+5) -> CLASSIC_MIB3.
     * All three must hit together; any single one can misfire (other head units with sparse lists, non-Audi DELPHI parts).
     * Everything else (including unknown head units) -> EVENT_LINK.
     */
    enum class LinkGeneration { CLASSIC_MIB3, EVENT_LINK }

    /**
     * Decide the link generation from 0x1D01 self-reported signals (see the [LinkGeneration] docs).
     * Insufficient signal (lists not provided / identity missing) -> EVENT_LINK (pre-0.1.13 compatible behavior).
     */
    fun linkGenerationOf(signals: Signals): LinkGeneration {
        val manufacturer = signals.manufacturer?.lowercase().orEmpty()
        val model = signals.model?.lowercase().orEmpty()
        val listTotal = (signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0)
        val listsSparse = signals.sendIds != null && signals.receiveIds != null && listTotal in 1..9
        val isDelphiAudi = manufacturer.contains("delphi") && model.contains("audi")
        return if (isDelphiAudi && listsSparse) {
            LinkGeneration.CLASSIC_MIB3
        } else {
            LinkGeneration.EVENT_LINK
        }
    }

    /** Profile input: every field is nullable, use whatever signals have arrived. */
    data class Signals(
        val name: String? = null,
        val model: String? = null,
        val manufacturer: String? = null,
        /** AirPlay features bitmap (mDNS TXT / /info); null = not obtained. */
        val features: Long? = null,
        /** AirPlay stack version (mDNS TXT srcvers / 0x4301 sourceVersion). */
        val srcvers: String? = null,
        /** 0x1D01 param 6: list of message IDs the head unit declares it will SEND; null = not provided. */
        val sendIds: Set<Int>? = null,
        /** 0x1D01 param 7: list of message IDs the head unit declares it can RECEIVE; null = not provided. */
        val receiveIds: Set<Int>? = null,
    )

    data class Profile(
        val era: Era,
        val wifiGen: WifiGen,
        /** Identity key (same source as MfiLengthMemory/AudioProfileMemory, for merging later Memory tables). */
        val identityKey: String,
        /** Evidence (explainability: the original signal behind each conclusion). */
        val reasons: List<String>,
    )

    fun classify(signals: Signals): Profile {
        val reasons = ArrayList<String>()
        val pairing = AirPlayFeatures.supportsPairing(signals.features)
        val era =
            when {
                pairing == null -> Era.UNKNOWN
                !pairing -> {
                    reasons += "features(${signals.features?.let { "0x${it.toString(16)}" } ?: "?"}) has no bit38=HK_PAIRING_AND_ENCRYPT"
                    Era.PRE_HOMEKIT
                }

                else -> {
                    reasons += "features bit38=HK_PAIRING_AND_ENCRYPT declared"
                    Era.MODERN
                }
            }
        if (signals.srcvers != null) reasons += "srcvers=${signals.srcvers}"
        if (era != Era.PRE_HOMEKIT && signals.srcvers != null) {
            // Prior hint (does not change the verdict): old version number + no pairing declared -> v280 optional-pairing tier
            val v = signals.srcvers.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0
            if (v in 1..36599 && era == Era.UNKNOWN) reasons += "srcvers hints v280 optional-pairing tier (prior only)"
        }

        val wifiGen =
            when {
                signals.sendIds == null && signals.receiveIds == null -> {
                    reasons += "0x1D01 provided no receive/send lists"
                    WifiGen.UNKNOWN
                }

                signals.sendIds?.contains(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true ||
                    signals.receiveIds?.contains(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) == true -> {
                    reasons += "lists declare 0x5703/0x5702 (legacy protocol present)"
                    WifiGen.LEGACY
                }

                (signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0) >= 10 -> {
                    // Lists complete and fairly full yet no 0x5703/0x5702: BMW iD9 shape (both lists 10+ items).
                    // "Not declared" alone must not mean new: the Audi list only has CarPlay lifecycle messages (measured
                    // send list 2 items / receive list 5 items, none 0x57xx) yet the legacy protocol works fine, so
                    // sparse lists do not count; conservative UNKNOWN keeps the legacy path (0x5703 has a timeout fallback)
                    reasons += "lists complete (${(signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0)} items) yet no " +
                        "0x5703/0x5702; AP info comes from 0x4301"
                    WifiGen.NEW
                }

                (signals.sendIds != null || signals.receiveIds != null) -> {
                    reasons += "lists sparse and 0x5703/0x5702 not declared: not evidence of a \"new protocol\", conservatively " +
                        "treated as legacy (timeout fallback)"
                    WifiGen.UNKNOWN
                }

                else -> WifiGen.UNKNOWN
            }
        return Profile(era, wifiGen, identityKeyOf(signals), reasons)
    }

    /** Original identity text (name+model+manufacturer, verbatim as in 0x1D01; skipped when empty). */
    fun identityKeyOf(signals: Signals): String =
        listOfNotNull(
            signals.name?.takeIf { it.isNotBlank() },
            signals.model?.takeIf { it.isNotBlank() },
            signals.manufacturer?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { "unknown" }

    /** One readable verdict line (goes to the file log). */
    fun describe(profile: Profile): String =
        "PROFILE: era=${profile.era} wifiGen=${profile.wifiGen} key=${MfiLengthMemory.receiverKey(profile.identityKey)}" +
            " evidence[" + profile.reasons.joinToString("; ") + "]"
}
