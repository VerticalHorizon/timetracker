package io.github.verticalhorizon.timetracker.data

/** Groups tasks. [color] is an index into the palette in ui/theme/Theme.kt. */
data class Category(val id: Long, val name: String, val color: Int)

/**
 * Where a task's time comes from: tracker [slot] (0 = T1) of the tracker [deviceId].
 * [lastTotal] is the part of that tracker's never-reset total already credited to
 * the task, as seen during the tracker's boot [bootId].
 */
data class TimerLink(val slot: Int, val deviceId: Long, val bootId: Long, val lastTotal: Long)

data class Task(
    val id: Long,
    val name: String,
    val categoryId: Long? = null,
    val seconds: Long = 0,
    val link: TimerLink? = null,
)

data class AppData(
    val categories: List<Category> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val deviceAddress: String? = null,
    val nextId: Long = 1,
)
