package io.github.verticalhorizon.timetracker.ble

import java.util.UUID

/** One tracker row (T1…): the time on the display and the never-reset total, in seconds. */
data class TrackerSlot(val shown: Long, val total: Long)

data class Countdown(val duration: Long, val remaining: Long, val running: Boolean)

/** A snapshot of all timers, as published by the tracker. */
data class TrackerState(
    val ackSeq: Int,
    val ackStatus: Int,
    /** Index of the running tracker, -1 when none runs. */
    val running: Int,
    val deviceId: Long,
    val bootId: Long,
    val trackers: List<TrackerSlot>,
    val countdowns: List<Countdown>,
)

/** Byte layout shared with the firmware: docs/ble-protocol.md */
object TrackerProtocol {
    val SERVICE: UUID = UUID.fromString("603a0001-2e1a-4102-9367-4c8626517b1d")
    val STATE: UUID = UUID.fromString("603a0002-2e1a-4102-9367-4c8626517b1d")
    val COMMAND: UUID = UUID.fromString("603a0003-2e1a-4102-9367-4c8626517b1d")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val VERSION = 1
    const val ACK_OK = 0
    const val MIN_COUNTDOWN = 60L
    const val MAX_COUNTDOWN = 24 * 3600L
    private const val HEADER = 14

    /** Parses a snapshot, or returns null if it is truncated or speaks another protocol version. */
    fun parse(b: ByteArray): TrackerState? {
        if (b.size < HEADER || b.u8(0) != VERSION) return null
        val trackerCount = b.u8(12)
        val countdownCount = b.u8(13)
        if (b.size < HEADER + 8 * trackerCount + 9 * countdownCount) return null

        val trackers = List(trackerCount) { i ->
            val o = HEADER + 8 * i
            TrackerSlot(shown = b.u32(o), total = b.u32(o + 4))
        }
        val base = HEADER + 8 * trackerCount
        val countdowns = List(countdownCount) { i ->
            val o = base + 9 * i
            Countdown(duration = b.u32(o), remaining = b.i32(o + 4).toLong(), running = b[o + 8].toInt() != 0)
        }
        return TrackerState(
            ackSeq = b.u8(1),
            ackStatus = b.u8(2),
            running = b[3].toInt(),
            deviceId = b.u32(4),
            bootId = b.u32(8),
            trackers = trackers,
            countdowns = countdowns,
        )
    }

    fun resetTracker(seq: Int, tracker: Int): ByteArray =
        byteArrayOf(0x01, seq.toByte(), tracker.toByte())

    fun setCountdown(seq: Int, countdown: Int, seconds: Long): ByteArray {
        val s = seconds.toInt()
        return byteArrayOf(
            0x02, seq.toByte(), countdown.toByte(),
            s.toByte(), (s shr 8).toByte(), (s shr 16).toByte(), (s shr 24).toByte(),
        )
    }

    private fun ByteArray.u8(o: Int) = this[o].toInt() and 0xFF
    private fun ByteArray.i32(o: Int) = u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)
    private fun ByteArray.u32(o: Int) = i32(o).toLong() and 0xFFFFFFFFL
}
