package io.github.ezn24.cardeck.neo.airplay

import java.io.IOException

/** TLV8 request/response transport abstraction: the real implementation uses RTSP POST; tests connect a mock receiver directly. */
fun interface TlvTransport {
    /** Sends one TLV8 request body and returns the receiver's TLV8 response body. */
    fun exchange(
        path: String,
        body: ByteArray,
    ): ByteArray
}

/** Long-term identity of the phone (controller): ed25519 key pair + pairing ID, generated on first use. */
class ControllerIdentity(
    val pairingId: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

/**
 * AirPlay /pair-setup client (M1/M3/M5).
 * Reference receiver: fixed-code SRP-6a ("Pair-Setup"/"3939"), then the long-term ed25519 public keys are exchanged inside an encrypted envelope.
 */
class PairSetupClient(
    private val identity: ControllerIdentity,
    private val transport: TlvTransport,
    private val log: (String) -> Unit,
) {
    /** On completion the receiver has stored our long-term public key; returns the receiver's static identity (from M6, optional use). */
    fun run(): ReceiverIdentity? {
        // M1: request SRP parameters (Method=None + State=1; see catplay PairSetup::start)
        val m2 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_METHOD, byteArrayOf(0)),
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                        ),
                    ),
                ),
            )
        val salt = m2[Tlv8.TYPE_SALT] ?: throw IOException("pair-setup M2 missing salt")
        val serverPublic = m2[Tlv8.TYPE_PUBLIC_KEY] ?: throw IOException("pair-setup M2 missing publicKey")
        log("pair-setup: M2 received SRP parameters salt=${salt.size}B B=${serverPublic.size}B")

        // M3: client public key A + proof M1
        val srp = Srp6aClient.start(salt, serverPublic)
        val clientProof = srp.clientProof() ?: throw IOException("pair-setup: SRP B invalid")
        val m4 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(3)),
                            Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, srp.publicKeyA),
                            Tlv8.Item(Tlv8.TYPE_PROOF, clientProof),
                        ),
                    ),
                ),
            )
        val serverProof = m4[Tlv8.TYPE_PROOF] ?: throw IOException("pair-setup M4 missing proof")
        if (!srp.serverProofValid(serverProof)) throw IOException("pair-setup: server proof verification failed")
        val sessionKey = srp.sessionKey ?: throw IOException("pair-setup: session key missing")
        log("pair-setup: M4 SRP mutual verification passed")

        // M5: submit the long-term public key in an encrypted envelope
        val encryptionKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Encrypt-Salt".toByteArray(),
                "Pair-Setup-Encrypt-Info".toByteArray(),
            )
        val controllerSignKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Controller-Sign-Salt".toByteArray(),
                "Pair-Setup-Controller-Sign-Info".toByteArray(),
            )
        val identifierBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature =
            AirPlayCrypto.ed25519Sign(
                identity.privateKey,
                controllerSignKey + identifierBytes + identity.publicKey,
            )
        val sub =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_IDENTIFIER, identifierBytes),
                    Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, identity.publicKey),
                    Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                ),
            )
        val m6 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-setup",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(5)),
                            Tlv8.Item(
                                Tlv8.TYPE_ENCRYPTED_DATA,
                                AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg05"), sub),
                            ),
                        ),
                    ),
                ),
            )
        if (m6[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt() != 6) {
            throw IOException("pair-setup M6 state is not 6: ${m6[Tlv8.TYPE_ERROR]?.firstOrNull()}")
        }
        log("pair-setup: ✓ M6 done, receiver stored our long-term public key")

        // Parse the receiver identity in the M6 encrypted envelope (integrity check)
        val encrypted = m6[Tlv8.TYPE_ENCRYPTED_DATA] ?: return null
        val inner =
            Tlv8.decode(
                AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PS-Msg06"), encrypted),
            )
        val receiverId = inner[Tlv8.TYPE_IDENTIFIER] ?: return null
        val receiverKey = inner[Tlv8.TYPE_PUBLIC_KEY] ?: return null
        val receiverSignature = inner[Tlv8.TYPE_SIGNATURE] ?: return null
        val accessorySignKey =
            AirPlayCrypto.hkdfSha512(
                sessionKey,
                "Pair-Setup-Accessory-Sign-Salt".toByteArray(),
                "Pair-Setup-Accessory-Sign-Info".toByteArray(),
            )
        val valid =
            AirPlayCrypto.ed25519Verify(
                receiverKey,
                accessorySignKey + receiverId + receiverKey,
                receiverSignature,
            )
        log(
            "pair-setup: receiver identity id=${receiverId.toString(Charsets.UTF_8)} " +
                "pub=${receiverKey.toHexString().take(16)}… signature valid=$valid",
        )
        return ReceiverIdentity(receiverId.toString(Charsets.UTF_8), receiverKey)
    }

    data class ReceiverIdentity(
        val pairingId: String,
        val publicKey: ByteArray,
    )
}

/**
 * AirPlay /pair-verify client (M1/M3): per-session ephemeral X25519 + long-term ed25519 signature,
 * yielding the control-channel ChaCha20 keys on success.
 */
class PairVerifyClient(
    private val identity: ControllerIdentity,
    private val transport: TlvTransport,
    private val log: (String) -> Unit,
) {
    class ControlKeys(
        val phoneWriteKey: ByteArray,
        val phoneReadKey: ByteArray,
        val sharedSecret: ByteArray,
    )

    fun run(receiverStaticPublicKey: ByteArray? = null): ControlKeys {
        // M1: ephemeral X25519 public key
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-verify",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(1)),
                            Tlv8.Item(Tlv8.TYPE_PUBLIC_KEY, ephemeral.publicKey),
                        ),
                    ),
                ),
            )
        val receiverEphemeral = m2[Tlv8.TYPE_PUBLIC_KEY] ?: throw IOException("pair-verify M2 missing publicKey")
        val encrypted = m2[Tlv8.TYPE_ENCRYPTED_DATA] ?: throw IOException("pair-verify M2 missing encryptedData")

        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, receiverEphemeral)
        val encryptionKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Pair-Verify-Encrypt-Salt".toByteArray(),
                "Pair-Verify-Encrypt-Info".toByteArray(),
            )
        val inner =
            Tlv8.decode(
                AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg02"), encrypted),
            )
        val receiverId = inner[Tlv8.TYPE_IDENTIFIER] ?: throw IOException("pair-verify M2 envelope missing identifier")
        val receiverSignature = inner[Tlv8.TYPE_SIGNATURE] ?: throw IOException("pair-verify M2 envelope missing signature")

        // Verify with the receiver static public key provided by mDNS/0x4301 (verify if present, skip otherwise)
        if (receiverStaticPublicKey != null) {
            val valid =
                AirPlayCrypto.ed25519Verify(
                    receiverStaticPublicKey,
                    receiverEphemeral + receiverId + ephemeral.publicKey,
                    receiverSignature,
                )
            log("pair-verify: receiver signature check (against pk)=$valid")
        }

        // M3: our long-term signature
        val identifierBytes = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature =
            AirPlayCrypto.ed25519Sign(
                identity.privateKey,
                ephemeral.publicKey + identifierBytes + receiverEphemeral,
            )
        val sub =
            Tlv8.encode(
                listOf(
                    Tlv8.Item(Tlv8.TYPE_IDENTIFIER, identifierBytes),
                    Tlv8.Item(Tlv8.TYPE_SIGNATURE, signature),
                ),
            )
        val m4 =
            Tlv8.decode(
                transport.exchange(
                    "/pair-verify",
                    Tlv8.encode(
                        listOf(
                            Tlv8.Item(Tlv8.TYPE_STATE, byteArrayOf(3)),
                            Tlv8.Item(
                                Tlv8.TYPE_ENCRYPTED_DATA,
                                AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub),
                            ),
                        ),
                    ),
                ),
            )
        if (m4[Tlv8.TYPE_STATE]?.firstOrNull()?.toInt() != 4) {
            throw IOException("pair-verify M4 state is not 4: ${m4[Tlv8.TYPE_ERROR]?.firstOrNull()}")
        }
        log("pair-verify: ✓ done, control-channel keys derived")

        // Direction as in the receiver implementation: receiver readKey="Control-Write-..." (the direction we send)
        val phoneWriteKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Control-Salt".toByteArray(),
                "Control-Write-Encryption-Key".toByteArray(),
            )
        val phoneReadKey =
            AirPlayCrypto.hkdfSha512(
                shared,
                "Control-Salt".toByteArray(),
                "Control-Read-Encryption-Key".toByteArray(),
            )
        return ControlKeys(phoneWriteKey, phoneReadKey, shared)
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
