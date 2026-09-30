package com.aiway.nativeapp

import android.content.Context
import org.json.JSONObject
import java.io.File

class ProjectStore(context: Context) {
    private val file = File(context.filesDir, "workspace.json")

    fun load(): LinkedHashMap<String, String> {
        if (!file.exists()) return linkedMapOf(
            "index.html" to "<h1>Hello from AiWay Native</h1>\n",
            "style.css" to "body { font-family: sans-serif; }\n",
            "app.js" to "console.log('AiWay Native');\n"
        )
        return try {
            val obj = JSONObject(file.readText())
            val out = linkedMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = obj.optString(k, "")
            }
            out
        } catch (_: Throwable) { linkedMapOf() }
    }

    fun save(files: Map<String, String>) {
        val obj = JSONObject()
        files.forEach { (k, v) -> obj.put(k, v) }
        file.writeText(obj.toString())
    }
}
