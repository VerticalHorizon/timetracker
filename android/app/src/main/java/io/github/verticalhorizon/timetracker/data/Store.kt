package io.github.verticalhorizon.timetracker.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Keeps [AppData] in a small JSON file in the app's private storage. */
class Store(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    fun load(): AppData {
        val bytes = try {
            file.readFully()
        } catch (e: FileNotFoundException) {
            return AppData()
        }
        return try {
            decode(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: JSONException) {
            // Keep the unreadable file for inspection rather than overwrite it later
            file.baseFile.renameTo(File("${file.baseFile.path}.broken-${System.currentTimeMillis()}"))
            AppData()
        }
    }

    @Synchronized
    fun save(data: AppData) {
        val out = file.startWrite()
        try {
            out.write(encode(data).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
        }
    }

    companion object {
        private const val FILE_NAME = "timetracker.json"

        fun encode(d: AppData): JSONObject = JSONObject().apply {
            put("version", 1)
            put("nextId", d.nextId)
            d.deviceAddress?.let { put("device", it) }
            put("categories", JSONArray().apply {
                d.categories.forEach { c ->
                    put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color))
                }
            })
            put("tasks", JSONArray().apply {
                d.tasks.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("name", t.name)
                        t.categoryId?.let { put("category", it) }
                        put("seconds", t.seconds)
                        t.link?.let { l ->
                            put("link", JSONObject()
                                .put("slot", l.slot)
                                .put("device", l.deviceId)
                                .put("boot", l.bootId)
                                .put("last", l.lastTotal))
                        }
                    })
                }
            })
        }

        fun decode(o: JSONObject): AppData {
            val categories = o.optJSONArray("categories").objects().map {
                Category(it.getLong("id"), it.getString("name"), it.optInt("color"))
            }
            val tasks = o.optJSONArray("tasks").objects().map {
                Task(
                    id = it.getLong("id"),
                    name = it.getString("name"),
                    categoryId = if (it.has("category")) it.getLong("category") else null,
                    seconds = it.optLong("seconds"),
                    link = it.optJSONObject("link")?.let { l ->
                        TimerLink(l.getInt("slot"), l.getLong("device"), l.getLong("boot"), l.getLong("last"))
                    },
                )
            }
            val maxId = (categories.map { it.id } + tasks.map { it.id }).maxOrNull() ?: 0
            return AppData(
                categories = categories,
                tasks = tasks,
                deviceAddress = if (o.has("device")) o.getString("device") else null,
                nextId = maxOf(o.optLong("nextId", 1), maxId + 1),
            )
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else List(length()) { getJSONObject(it) }
    }
}
