package io.github.verticalhorizon.timetracker.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Builds snapshots the way buildState() in the firmware does, then parses them. */
class TrackerProtocolTest {

    private fun ByteArrayOutputStream.u32(v: Long) {
        for (shift in 0 until 32 step 8) write(((v shr shift) and 0xFF).toInt())
    }

    private fun snapshot(
        version: Int = 1,
        running: Int = 2,
        trackers: List<Pair<Long, Long>> = List(5) { (it * 10L) to (it * 100L) },
        countdowns: List<Triple<Long, Long, Boolean>> = listOf(
            Triple(300L, 120L, true), Triple(1800L, 1800L, false), Triple(5400L, -1L, false),
        ),
    ): ByteArray = ByteArrayOutputStream().apply {
        write(version)
        write(42) // ack seq
        write(0) // ack status
        write(running and 0xFF)
        u32(0xDEADBEEFL) // device id
        u32(0x01020304L) // boot id
        write(trackers.size)
        write(countdowns.size)
        trackers.forEach { (shown, total) -> u32(shown); u32(total) }
        countdowns.forEach { (duration, remaining, on) -> u32(duration); u32(remaining and 0xFFFFFFFFL); write(if (on) 1 else 0) }
    }.toByteArray()

    @Test
    fun `parses a full snapshot`() {
        val bytes = snapshot()
        assertEquals(14 + 5 * 8 + 3 * 9, bytes.size) // 81, as documented
        val s = TrackerProtocol.parse(bytes)!!
        assertEquals(42, s.ackSeq)
        assertEquals(0, s.ackStatus)
        assertEquals(2, s.running)
        assertEquals(0xDEADBEEFL, s.deviceId)
        assertEquals(0x01020304L, s.bootId)
        assertEquals(5, s.trackers.size)
        assertEquals(TrackerSlot(shown = 30, total = 300), s.trackers[3])
        assertEquals(Countdown(300, 120, true), s.countdowns[0])
        assertEquals(Countdown(5400, -1, false), s.countdowns[2])
    }

    @Test
    fun `no running tracker is minus one`() {
        assertEquals(-1, TrackerProtocol.parse(snapshot(running = -1))!!.running)
    }

    @Test
    fun `large totals stay unsigned`() {
        val s = TrackerProtocol.parse(snapshot(trackers = listOf(0L to 0xFFFFFFF0L)))!!
        assertEquals(0xFFFFFFF0L, s.trackers[0].total)
    }

    @Test
    fun `adapts to other tracker and countdown counts`() {
        val s = TrackerProtocol.parse(snapshot(trackers = List(8) { 1L to 2L }, countdowns = emptyList()))!!
        assertEquals(8, s.trackers.size)
        assertTrue(s.countdowns.isEmpty())
    }

    @Test
    fun `a notification cut short by a small MTU is rejected`() {
        assertNull(TrackerProtocol.parse(snapshot().copyOf(20)))
        assertNull(TrackerProtocol.parse(snapshot().copyOf(80)))
        assertNotNull(TrackerProtocol.parse(snapshot()))
    }

    @Test
    fun `another protocol version is rejected`() {
        assertNull(TrackerProtocol.parse(snapshot(version = 2)))
        assertNull(TrackerProtocol.parse(ByteArray(0)))
    }

    @Test
    fun `encodes commands`() {
        assertArrayEquals(byteArrayOf(0x01, 9, 4), TrackerProtocol.resetTracker(seq = 9, tracker = 4))
        // 86400 s = 0x00015180, little-endian
        assertArrayEquals(
            byteArrayOf(0x02, 200.toByte(), 1, 0x80.toByte(), 0x51, 0x01, 0x00),
            TrackerProtocol.setCountdown(seq = 200, countdown = 1, seconds = 86_400),
        )
    }
}
