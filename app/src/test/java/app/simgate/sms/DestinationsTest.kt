package app.simgate.sms

import app.simgate.data.DestinationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationsTest {
    private val africa = listOf("+20 # Egypt", "+21", "+22", "+23", "+24", "+25", "+26", "+27", "+290", "+291")

    @Test fun blocklistBlocksAfrica() {
        val f = DestinationFilter(blocklist = africa)
        assertFalse(Destinations.check("+234 803 123 4567", f).allowed)
        assertFalse(Destinations.check("+27821234567", f).allowed)
        assertTrue(Destinations.check("+44 7700 900123", f).allowed)
        assertTrue(Destinations.check("+299 123456", f).allowed) // Greenland is not Africa
    }

    @Test fun internationalPrefix00IsNormalised() {
        val f = DestinationFilter(blocklist = listOf("+234"))
        assertEquals("+2348031234567", Destinations.check("002348031234567", f).normalized)
        assertFalse(Destinations.check("002348031234567", f).allowed)
    }

    @Test fun allowlistOnly() {
        val f = DestinationFilter(allowlistOnly = true, allowlist = listOf("+44", "+33 6"))
        assertTrue(Destinations.check("+44 7700 900123", f).allowed)
        assertTrue(Destinations.check("+33 6 12 34 56 78", f).allowed)
        assertFalse(Destinations.check("+33 1 23 45 67 89", f).allowed)
        assertFalse(Destinations.check("+1 202 555 0147", f).allowed)
    }

    @Test fun blocklistWinsOverAllowlist() {
        val f = DestinationFilter(allowlistOnly = true, allowlist = listOf("+44"), blocklist = listOf("+44 9"))
        assertFalse(Destinations.check("+44 909 123 4567", f).allowed)
    }

    @Test fun localNumbersUseCountryCodeAndTrunkPrefix() {
        val f = DestinationFilter(defaultCountryCode = "44", trunkPrefix = "0", allowlistOnly = true, allowlist = listOf("+44"))
        val v = Destinations.check("07700 900123", f)
        assertEquals("+447700900123", v.normalized)
        assertTrue(v.allowed)
    }

    @Test fun shortCodesCanBeBlocked() {
        assertTrue(Destinations.check("900", DestinationFilter()).allowed)
        assertFalse(Destinations.check("900", DestinationFilter(blockShortCodes = true)).allowed)
    }

    @Test fun localNumbersCanBeBlocked() {
        assertFalse(Destinations.check("89991234567", DestinationFilter(allowLocal = false)).allowed)
    }

    @Test fun disabledFilterAllowsEverything() {
        assertTrue(Destinations.check("+2348031234567", DestinationFilter(enabled = false, blocklist = listOf("+2"))).allowed)
    }

    @Test fun ruleValidation() {
        assertTrue(Destinations.isValidRule("+44 7700*  # UK test range"))
        assertTrue(Destinations.isValidRule("# comment only"))
        assertFalse(Destinations.isValidRule("+44abc"))
    }
}
