package io.github.ezn24.droidcar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression protection for the ctrl mDNS advertisement identity fields.
 *
 * 2026-10-19 Audi MMI 3315 real-car comparison (`docs/PROTOCOL-NOTES.md` section 9i):
 * with 0.1.6 using `name=sender` + `srcvers=950.7.1` the head unit sent 0x4301 within 0.65 seconds;
 * after 0.1.7 changed to catplay's `name=iPhone` + `srcvers=566.25.21`, the head unit **stopped even sending ctrl-int
 * probes** and 0x4301 never arrived. These two fields are the only phone identity visible to the head unit on WiFi,
 * changing them is equivalent to swapping the device -- a test must block another "copy from catplay" change.
 *
 * It also locks in that `_device-info._tcp` is no longer registered: Android's NsdServiceInfo does not accept port=0
 * (a real device reports `The port is missing`), and it is an extra advertisement of unverified benefit, whose failure only creates noise.
 */
class CarPlayCtrlAdvertiserTest {
    @Test
    fun ctrlTxtKeepsValuesVerifiedOnAudi() {
        val srcvers = CarPlayCtrlAdvertiser.advertisedSrcversForTest()
        val name = CarPlayCtrlAdvertiser.advertisedServiceNameForTest()
        assertEquals("The head unit branches on this version number: changing it stops even ctrl probes", "950.7.1", srcvers)
        assertEquals("Instance name measured working since 0.1.6", "sender", name)
        assertTrue("The service type must not change", CarPlayCtrlAdvertiser.SERVICE_TYPE == "_carplay-ctrl._tcp")
    }

    @Test
    fun nonAdapterResponseIsUnchanged() {
        val expected = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        assertEquals(expected, CarPlayCtrlAdvertiser.ctrlResponse(SMARTBOX_CONNECT, adapterMode = false))
        assertEquals(expected, CarPlayCtrlAdvertiser.ctrlResponse("GET /info HTTP/1.1\r\n\r\n", adapterMode = false))
    }

    @Test
    fun adapterConnectGetsKeepAlive200() {
        // smartBox invite captured by unapple-phone (UA AirPlay/320.17.6)
        val response = CarPlayCtrlAdvertiser.ctrlResponse(SMARTBOX_CONNECT, adapterMode = true)
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", response)
        assertTrue(!response.contains("Connection: close"))
        val withCseq = CarPlayCtrlAdvertiser.ctrlResponse("GET /ctrl-int/1/connect RTSP/1.0\r\nCSeq: 3\r\n\r\n", adapterMode = true)
        assertEquals("RTSP/1.0 200 OK\r\nContent-Length: 0\r\nCSeq: 3\r\n\r\n", withCseq)
        // Other requests keep the old reply even in adapter mode.
        assertEquals(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            CarPlayCtrlAdvertiser.ctrlResponse("GET /info HTTP/1.1\r\n\r\n", adapterMode = true),
        )
    }

    @Test
    fun deviceInfoIsNotAdvertised() {
        // _device-info._tcp was once registered with port=0 and necessarily failed (The port is missing).
        // To restore it in the future, first confirm NsdManager accepts a valid port and that this head unit really needs it.
        assertTrue(
            "_device-info._tcp should no longer be advertised",
            !CarPlayCtrlAdvertiser.advertisedServiceTypesForTest().contains("_device-info._tcp"),
        )
    }
}

private const val SMARTBOX_CONNECT =
    "GET /ctrl-int/1/connect HTTP/1.1\r\nHost: phone.local.\r\nUser-Agent: AirPlay/320.17.6\r\n" +
        "AirPlay-Receiver-Device-ID: 73588229206\r\n\r\n"
