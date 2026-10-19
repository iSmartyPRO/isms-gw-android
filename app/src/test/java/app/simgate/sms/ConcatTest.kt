package app.simgate.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConcatTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // SMSC +79150000000, SMS-DELIVER with UDHI, OA +79161234567, 8-bit concat IE ref 0x2A part 1/3.
    private val deliver = hex("07 917951000000F0 44 0B 919761214365F7 00 00 62101911000021 A0 050003 2A 03 01 C8329BFD06")

    @Test fun deliverWith8BitReference() = assertEquals(Concat.Part(0x2A, 3, 1), Concat.parse(deliver))

    @Test fun submitWith16BitReferenceAndRelativeValidity() {
        // No SMSC, SMS-SUBMIT (VPF relative) with UDHI, DA +79161234567, 16-bit concat IE ref 0x0102 part 2/2.
        val submit = hex("00 51 00 0B 919761214365F7 00 00 AA 0A 06 0804 0102 02 02 C832")
        assertEquals(Concat.Part(0x0102, 2, 2), Concat.parse(submit))
    }

    @Test fun singlePartMessageHasNoHeader() {
        val plain = hex("07 917951000000F0 04 0B 919761214365F7 00 00 62101911000021 05 C8329BFD06")
        assertNull(Concat.parse(plain))
    }

    @Test fun truncatedPduIsIgnored() = assertNull(Concat.parse(deliver.copyOf(20)))

    @Test fun mergesPartsInOrderAndKeepsIndexes() {
        fun m(i: Int, body: String, date: Long) = IccMessage(i, "+79161234567", body, date, "read")
        val merged = Concat.merge(listOf(
            m(7, "world", 2000) to Concat.Part(5, 2, 2),
            m(3, "single", 500) to null,
            m(4, "hello ", 1000) to Concat.Part(5, 2, 1),
        ))
        assertEquals(2, merged.size)
        val long = merged.first { it.total == 2 }
        assertEquals("hello world", long.body)
        assertEquals(listOf(4, 7), long.parts)
        assertEquals(4, long.index)
        assertEquals(1000L, long.date)
    }

    @Test fun missingPartAndReusedReference() {
        fun m(i: Int, body: String) = IccMessage(i, "+7916", body, 1, "read")
        val merged = Concat.merge(listOf(
            m(1, "a1") to Concat.Part(9, 2, 1), m(2, "a2") to Concat.Part(9, 2, 2),
            m(5, "b1") to Concat.Part(9, 2, 1),
        ))
        assertEquals(listOf("a1a2", "b1"), merged.map { it.body })
        assertEquals(1, merged[1].received)
        assertEquals(2, merged[1].total)
    }
}
