package io.github.ezn24.cardeck.neo.airplay

/**
 * Frame encryption for the screen/audio streams (the three forms of the AirPlay `et` field).
 *
 * Selection logic of catplay `ScreenFrameCodec::new`:
 *  - went through /pair-setup/pair-verify -> ChaCha20-Poly1305 (derived from the shared secret);
 *  - went through /auth-setup (MFi-SAP) only -> AES-128-CTR (session key);
 *  - neither (`et = NONE`, old pre-HomeKit head units) -> **AES-128-CTR with an all-zero session key**
 *    (catplay comment: "There was never support for unencrypted screen sessions.
 *     ET = None maps to AES session where session_key before derivation is all zeroes.")
 *
 * Data point 2026-10-19: Audi 4xxx (e.g. 4851) may belong to the third kind: forcing
 * pair-setup on it always fails, while catplay connects by starting with a plaintext SETUP and streaming the screen over AES with a zero key.
 */
sealed class StreamFrameCipher {
    /** ChaCha20-Poly1305 seal of video frames (16B tag); keepAlive/config frames are not encrypted. */
    class ChaCha(
        val key: ByteArray,
    ) : StreamFrameCipher()

    /** Streaming AES-128-CTR encryption of video frames (no tag; keystream continues across frames). */
    class AesCtr(
        val key: ByteArray,
        val iv: ByteArray,
    ) : StreamFrameCipher()
}

object AirPlayStreamKeys {
    /**
     * ChaCha stream key: HKDF-SHA512(shared_secret, "DataStream-Salt<connId>", "DataStream-Output-Encryption-Key").
     * Same derivation as catplay `create_chacha_ciphers` and the receiver's ScreenStream.
     */
    fun chachaStreamKey(
        sharedSecret: ByteArray,
        connectionId: Long,
    ): ByteArray =
        AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )

    /** Session key for `et=NONE`: 16 zero bytes (same as catplay ScreenFrameCodec::new). */
    val ZERO_SESSION_KEY: ByteArray = ByteArray(16)

    /**
     * AES stream key/IV (byte-aligned with catplay `derive_aes_stream_keys`):
     *   key = SHA512("AirPlayStreamKey" + connId(decimal) + sessionKey)[0..16]
     *   iv  = SHA512("AirPlayStreamIV"  + connId(decimal) + sessionKey)[0..16]
     */
    fun aesStreamKeys(
        sessionKey: ByteArray,
        connectionId: Long,
    ): Pair<ByteArray, ByteArray> {
        fun digest(prefix: String): ByteArray =
            AirPlayCrypto
                .sha512(
                    prefix.toByteArray(Charsets.US_ASCII),
                    connectionId.toString().toByteArray(Charsets.US_ASCII),
                    sessionKey,
                ).copyOfRange(0, 16)
        return digest("AirPlayStreamKey") to digest("AirPlayStreamIV")
    }
}
