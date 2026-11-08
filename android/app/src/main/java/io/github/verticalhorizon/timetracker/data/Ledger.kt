package io.github.verticalhorizon.timetracker.data

import io.github.verticalhorizon.timetracker.ble.TrackerState

/**
 * Turns tracker totals into task time. The rules are explained under
 * "How the app counts time" in docs/ble-protocol.md.
 *
 * A task only ever gains seconds its own tracker counted while linked to it, so
 * switching trackers can't add time the task didn't get.
 */
object Ledger {

    /** Credits [state] to every linked task. Returns [tasks] itself when nothing changed. */
    fun credit(tasks: List<Task>, state: TrackerState): List<Task> {
        var changed = false
        val result = tasks.map { task -> credit(task, state).also { if (it !== task) changed = true } }
        return if (changed) result else tasks
    }

    fun credit(task: Task, state: TrackerState): Task {
        val link = task.link ?: return task
        val total = state.trackers.getOrNull(link.slot)?.total ?: return task
        return when {
            // Another tracker, or this one's memory was wiped: start over, credit nothing
            link.deviceId != state.deviceId ->
                task.copy(link = link.copy(deviceId = state.deviceId, bootId = state.bootId, lastTotal = total))

            total > link.lastTotal ->
                task.copy(
                    seconds = task.seconds + (total - link.lastTotal),
                    link = link.copy(bootId = state.bootId, lastTotal = total),
                )

            // Nothing new, or a stale snapshot: totals never shrink within one boot
            link.bootId == state.bootId -> task

            // Restarted from a save older than what we saw: carry on from there
            else -> task.copy(link = link.copy(bootId = state.bootId, lastTotal = total))
        }
    }

    /** Takes [taskId] off its tracker and frees [slot], right before [slot] is reset for it. */
    fun detach(tasks: List<Task>, taskId: Long, slot: Int): List<Task> =
        tasks.map { if (it.id == taskId || it.link?.slot == slot) it.copy(link = null) else it }

    /**
     * Links [taskId] to [slot], counting from [acked]: the snapshot that acknowledged the
     * reset of [slot]. Seconds the tracker has shown since that reset count for the task.
     */
    fun attach(tasks: List<Task>, taskId: Long, slot: Int, acked: TrackerState): List<Task> {
        val tracker = acked.trackers[slot]
        val start = tracker.total - minOf(tracker.shown, tracker.total)
        val link = TimerLink(slot, acked.deviceId, acked.bootId, lastTotal = start)
        return tasks.map { if (it.id == taskId) it.copy(link = link) else it }
    }

    fun unlink(tasks: List<Task>, taskId: Long): List<Task> =
        tasks.map { if (it.id == taskId) it.copy(link = null) else it }
}
