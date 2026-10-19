package app.simgate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectLinkTest {
    private fun parsed(text: String): ConnectPayload {
        val r = parseConnectPayload(text)
        assertTrue("expected Parsed but was $r", r is Parsed)
        return (r as Parsed).payload
    }

    private fun failed(text: String): ConnectError {
        val r = parseConnectPayload(text)
        assertTrue("expected Failed but was $r", r is Failed)
        return (r as Failed).error
    }

    @Test fun correctLink() {
        val p = parsed("ismsgw://connect?v=1&url=wss%3A%2F%2Fsms.ismarty.pro%2Fws%2Fdevice&token=smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv&name=Office%20phone")
        assertEquals("wss://sms.ismarty.pro/ws/device", p.url)
        assertEquals("smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv", p.token)
        assertEquals("Office phone", p.name)
        assertFalse(p.insecure)
        assertTrue(p.knownToken)
    }

    @Test fun urlEncodingIsDecoded() {
        val p = parsed("ismsgw://connect?url=https%3A%2F%2Fhost.example%3A8443%2Fws%2Fdevice&token=smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv")
        assertEquals("https://host.example:8443/ws/device", p.url)
        assertNull(p.name)
    }

    @Test fun legacyTwoLineFormat() {
        val p = parsed("wss://sms.ismarty.pro/ws/device\nsmsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv")
        assertEquals("wss://sms.ismarty.pro/ws/device", p.url)
        assertEquals("smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv", p.token)
        assertTrue(p.knownToken)
    }

    @Test fun appLinkWithFragment() {
        val p = parsed("https://sms.ismarty.pro/connect#url=wss%3A%2F%2Fsms.ismarty.pro%2Fws%2Fdevice&token=smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv")
        assertEquals("wss://sms.ismarty.pro/ws/device", p.url)
        assertEquals("smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv", p.token)
    }

    @Test fun garbageIsRejected() {
        failed("just some random text")                                   // any error kind is fine
        assertEquals(ConnectError.Kind.EMPTY, failed("   ").kind)
        assertEquals(ConnectError.Kind.BAD_FORMAT, failed("onlyoneword").kind)
    }

    @Test fun nonStandardTokenIsAcceptedWithWarning() {
        val p = parsed("ismsgw://connect?url=wss%3A%2F%2Fsms.ismarty.pro%2Fws&token=legacy-token-123")
        assertEquals("legacy-token-123", p.token)
        assertFalse(p.knownToken)
        assertTrue(p.warnings().isNotEmpty())
    }

    @Test fun insecureSchemeIsFlagged() {
        val p = parsed("ismsgw://connect?url=ws%3A%2F%2F192.168.1.50%3A8080%2Fws&token=smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv")
        assertTrue(p.insecure)
        assertTrue(p.warnings().any())
        assertFalse(parsed("wss://sms.ismarty.pro/ws\nsmsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv").insecure)
    }

    @Test fun unknownVersionAsksToUpdate() {
        assertEquals(ConnectError.Kind.UNKNOWN_VERSION,
            failed("ismsgw://connect?v=99&url=wss%3A%2F%2Fsms.ismarty.pro%2Fws&token=smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv").kind)
    }

    @Test fun badUrlSchemeIsRejected() {
        assertEquals(ConnectError.Kind.BAD_URL,
            failed("ismsgw://connect?url=ftp%3A%2F%2Fhost%2Ffile&token=smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv").kind)
    }

    @Test fun nameIsTrimmedToLimit() {
        val long = "x".repeat(200)
        val p = parsed("ismsgw://connect?url=wss%3A%2F%2Fh%2Fws&token=smsgw_AbCd1234_0123456789abcdefghijklmnopqrstuv&name=$long")
        assertEquals(120, p.name!!.length)
    }
}
