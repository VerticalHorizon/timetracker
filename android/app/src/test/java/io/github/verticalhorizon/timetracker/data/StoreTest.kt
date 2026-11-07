package io.github.verticalhorizon.timetracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTest {

    @Test
    fun `round-trips everything`() {
        val data = AppData(
            categories = listOf(Category(1, "Work", 0), Category(2, "Study “deep”", 4)),
            tasks = listOf(
                Task(3, "Report", categoryId = 1, seconds = 5040, link = TimerLink(1, 0xFFFFFFFFL, 12345, 360_000)),
                Task(4, "Reading", seconds = 0),
            ),
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            nextId = 5,
        )
        val json = Store.encode(data).toString()
        assertEquals(data, Store.decode(JSONObject(json)))
    }

    @Test
    fun `an empty file is empty data`() {
        assertEquals(AppData(), Store.decode(JSONObject("{}")))
    }

    @Test
    fun `next id never collides with an existing one`() {
        val json = """{"nextId": 2, "tasks": [{"id": 9, "name": "x"}]}"""
        assertEquals(10, Store.decode(JSONObject(json)).nextId)
    }
}
