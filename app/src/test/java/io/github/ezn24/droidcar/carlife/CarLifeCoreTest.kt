package io.github.ezn24.droidcar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class CarLifeCoreTest {
    @Test fun commandFrameHasLengthAndServiceIdHeader() {
        val frame = CarLifeFraming.cmd(CarLifeProtocol.CMD_MD_INFO, byteArrayOf(1, 2, 3))
        assertEquals(CarLifeProtocol.HEAD_CMD + 3, frame.size)
        val head = frame.copyOfRange(0, CarLifeProtocol.HEAD_CMD)
        assertEquals(3, CarLifeFraming.bodyLen(CarLifeProtocol.CH_CMD, head))
        assertEquals(CarLifeProtocol.CMD_MD_INFO, CarLifeFraming.serviceId(CarLifeProtocol.CH_CMD, head))
    }

    @Test fun streamFrameCarriesTimestampAndServiceId() {
        val frame = CarLifeFraming.stream(CarLifeProtocol.VIDEO_DATA, ByteArray(10), timestamp = 1234)
        val head = frame.copyOfRange(0, CarLifeProtocol.HEAD_STREAM)
        assertEquals(10, CarLifeFraming.bodyLen(CarLifeProtocol.CH_VIDEO, head))
        val parsed = CarLifeFraming.parseStream(head, ByteArray(10))
        assertEquals(CarLifeProtocol.VIDEO_DATA, parsed.serviceId)
        assertEquals(1234, parsed.timestamp)
    }

    @Test fun protoRoundTrip() {
        val bytes =
            ProtoWriter()
                .int32(1, 1920)
                .int32(2, 720)
                .string(3, "DroidCar")
                .message(4, ProtoWriter().bool(1, true))
                .toByteArray()
        val reader = ProtoReader(bytes)
        assertEquals(1920, reader.int(1, 0))
        assertEquals(720, reader.int(2, 0))
        assertEquals("DroidCar", reader.string(3))
        assertEquals(1, reader.messages(4).size)
    }

    @Test fun aesKeyIsWrappedWithTheHeadUnitRsaKeyAndEncryptsBothWays() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val crypto = CarLifeCrypto()
        val request =
            crypto.aesKeyRequest(
                java.util.Base64
                    .getEncoder()
                    .encodeToString(pair.public.encoded),
            )!!
        assertFalse("not before the head unit acknowledged the key", crypto.enableOutgoing())
        crypto.armIncoming()
        assertTrue(crypto.enableOutgoing())

        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.DECRYPT_MODE, pair.private)
        val key =
            SecretKeySpec(
                rsa.doFinal(
                    java.util.Base64
                        .getDecoder()
                        .decode(request),
                ),
                "AES",
            )
        val aes = Cipher.getInstance("AES/ECB/PKCS5Padding")
        aes.init(Cipher.DECRYPT_MODE, key)
        val plain = "CarLife payload".toByteArray()
        val encrypted = crypto.encryptOut(plain)
        assertArrayEquals(plain, aes.doFinal(encrypted))
        assertArrayEquals(plain, crypto.decryptIn(encrypted))
    }
}
