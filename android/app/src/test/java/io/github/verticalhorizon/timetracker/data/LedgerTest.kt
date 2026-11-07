package io.github.verticalhorizon.timetracker.data

import io.github.verticalhorizon.timetracker.ble.TrackerSlot
import io.github.verticalhorizon.timetracker.ble.TrackerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LedgerTest {

    private val device = 0xCAFEL
    private val boot = 7L

    /** A snapshot where tracker i shows shown[i] and has counted totals[i] in all. */
    private fun state(
        totals: List<Long>,
        shown: List<Long> = totals,
        deviceId: Long = device,
        bootId: Long = boot,
    ) = TrackerState(
        ackSeq = 0, ackStatus = 0, running = -1, deviceId = deviceId, bootId = bootId,
        trackers = totals.indices.map { TrackerSlot(shown[it], totals[it]) },
        countdowns = emptyList(),
    )

    private fun linked(id: Long, slot: Int, lastTotal: Long, seconds: Long = 0) =
        Task(id, "task $id", seconds = seconds, link = TimerLink(slot, device, boot, lastTotal))

    @Test
    fun `credits what the linked tracker counted since last time`() {
        val task = linked(1, slot = 1, lastTotal = 100, seconds = 50)
        val result = Ledger.credit(task, state(listOf(999, 160)))
        assertEquals(110, result.seconds)
        assertEquals(160, result.link!!.lastTotal)
    }

    @Test
    fun `returns the same list when nothing changed`() {
        val tasks = listOf(linked(1, 0, lastTotal = 10), Task(2, "free"))
        assertSame(tasks, Ledger.credit(tasks, state(listOf(10, 500))))
    }

    @Test
    fun `a reset on the tracker loses nothing`() {
        // Long OK press: the display goes back to zero, the total keeps counting
        var task = linked(1, 0, lastTotal = 0)
        task = Ledger.credit(task, state(totals = listOf(3600), shown = listOf(3600)))
        task = Ledger.credit(task, state(totals = listOf(3600), shown = listOf(0)))
        task = Ledger.credit(task, state(totals = listOf(3660), shown = listOf(60)))
        assertEquals(3660, task.seconds)
    }

    @Test
    fun `time counted while the app was closed is picked up on the next connection`() {
        val task = linked(1, 0, lastTotal = 1000, seconds = 1000)
        assertEquals(1000 + 7200, Ledger.credit(task, state(listOf(1000 + 7200))).seconds)
    }

    @Test
    fun `a stale snapshot from the same boot is ignored`() {
        var task = linked(1, 0, lastTotal = 0)
        task = Ledger.credit(task, state(listOf(500)))
        task = Ledger.credit(task, state(listOf(480))) // late, out of order
        task = Ledger.credit(task, state(listOf(520)))
        assertEquals(520, task.seconds)
    }

    @Test
    fun `after a power cut the tracker's older save becomes the starting point`() {
        var task = linked(1, 0, lastTotal = 0)
        task = Ledger.credit(task, state(listOf(1000)))
        // Rebooted from a save taken 40 s before what we saw, then ran 10 s
        task = Ledger.credit(task, state(listOf(970), bootId = boot + 1))
        assertEquals(1000, task.seconds)
        task = Ledger.credit(task, state(listOf(1030), bootId = boot + 1))
        assertEquals(1060, task.seconds)
    }

    @Test
    fun `another tracker or a wiped one credits nothing until it counts`() {
        var task = linked(1, 0, lastTotal = 100, seconds = 100)
        task = Ledger.credit(task, state(listOf(5000), deviceId = 0xBEEFL))
        assertEquals(100, task.seconds)
        task = Ledger.credit(task, state(listOf(5030), deviceId = 0xBEEFL))
        assertEquals(130, task.seconds)
    }

    @Test
    fun `a link to a tracker the firmware doesn't have is left alone`() {
        val task = linked(1, slot = 7, lastTotal = 0)
        assertSame(task, Ledger.credit(task, state(listOf(100, 100))))
    }

    @Test
    fun `moving a task to another tracker keeps its time and adds only new time`() {
        // "Report" has 1 h on T1. T2 has 2 h that belong to nobody.
        var tasks = listOf(linked(1, slot = 0, lastTotal = 0))
        tasks = Ledger.credit(tasks, state(listOf(3600, 7200)))
        assertEquals(3600, tasks[0].seconds)

        // Relink to T2: detach, the tracker resets T2 (shown 0, total stays 7200) and acks
        tasks = Ledger.detach(tasks, taskId = 1, slot = 1)
        val ack = state(totals = listOf(3600, 7200), shown = listOf(3600, 0))
        tasks = Ledger.attach(tasks, taskId = 1, slot = 1, acked = ack)
        assertEquals(3600, tasks[0].seconds)

        // T1 keeps running, but it isn't the task's timer any more
        tasks = Ledger.credit(tasks, state(listOf(4200, 7200)))
        assertEquals(3600, tasks[0].seconds)

        // T2 runs 10 minutes
        tasks = Ledger.credit(tasks, state(listOf(4200, 7800)))
        assertEquals(3600 + 600, tasks[0].seconds)
    }

    @Test
    fun `seconds shown since the reset already count`() {
        val ack = state(totals = listOf(7202), shown = listOf(2))
        val tasks = Ledger.attach(listOf(Task(1, "t")), 1, 0, ack)
        assertEquals(7200, tasks[0].link!!.lastTotal)
        assertEquals(2, Ledger.credit(tasks, ack)[0].seconds)
    }

    @Test
    fun `taking a tracker from another task leaves that task its time`() {
        val tasks = listOf(linked(1, slot = 0, lastTotal = 0, seconds = 10), linked(2, slot = 1, lastTotal = 0, seconds = 99))
        val result = Ledger.detach(tasks, taskId = 1, slot = 1)
        assertNull(result[0].link)
        assertNull(result[1].link)
        assertEquals(99, result[1].seconds)
    }

    @Test
    fun `an unlinked task stops counting`() {
        val tasks = Ledger.unlink(listOf(linked(1, 0, lastTotal = 0, seconds = 42)), 1)
        assertEquals(tasks, Ledger.credit(tasks, state(listOf(1000))))
        assertEquals(42, tasks[0].seconds)
    }
}
