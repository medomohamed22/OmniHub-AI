package com.aiway.nativeapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiDirectClient(private val http: OkHttpClient) {
    data class Model(val slug: String, val displayName: String)
    data class AgentResult(val finalText: String, val changed: Boolean)

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(accessToken: String): List<Model> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error(apiError(r.code, body))
            val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("visibility") != "list") continue
                    val slug = o.optString("slug")
                    if (slug.isNotBlank()) add(Model(slug, o.optString("display_name").ifBlank { slug }))
                }
            }
        }
    }

    suspend fun runWorkspaceAgent(
        accessToken: String,
        model: String,
        files: MutableMap<String, String>,
        prompt: String,
        onTextDelta: (String) -> Unit = {},
        onTool: (String) -> Unit = {}
    ): AgentResult = withContext(Dispatchers.IO) {
        require(model.isNotBlank()) { "اختر موديل أولاً" }
        val input = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", "طلب المستخدم:\n$prompt\n\nمساحة العمل الحالية تحتوي ${files.size} ملف. استخدم أدوات workspace لقراءة الملفات اللازمة وتعديلها فعلياً. لا تفترض محتوى ملف قبل قراءته.")
        )
        var changed = false
        var finalText = ""

        repeat(12) { round ->
            val payload = JSONObject()
                .put("model", model)
                .put("instructions", "أنت وكيل برمجة داخل تطبيق Android اسمه AiWay. نفّذ طلب المستخدم على مساحة العمل المحلية باستخدام أدوات workspace. اقرأ قبل التعديل، غيّر أقل قدر لازم، حافظ على قابلية البناء، ولا تحذف ملفات إلا عند الحاجة. بعد الانتهاء اشرح باختصار ما فعلته بالعربية.")
                .put("input", input)
                .put("tools", workspaceTools())
                .put("parallel_tool_calls", false)
                .put("store", false)
                .put("stream", true)

            val response = streamResponse(accessToken, payload, onTextDelta)
            // Keep every output item (including reasoning items) as required for tool-call continuations.
            for (i in 0 until response.output.length()) input.put(response.output.get(i))
            val calls = mutableListOf<JSONObject>()
            for (i in 0 until response.output.length()) {
                val item = response.output.optJSONObject(i) ?: continue
                if (item.optString("type") == "function_call") calls += item
            }
            if (calls.isEmpty()) {
                finalText = response.text
                return@withContext AgentResult(finalText, changed)
            }

            calls.forEach { call ->
                val rawName = call.optString("name")
                val name = rawName.substringAfterLast('.')
                val args = runCatching { JSONObject(call.optString("arguments", "{}")) }.getOrElse { JSONObject() }
                onTool(name)
                val result = executeWorkspaceTool(name, args, files) { changed = true }
                input.put(JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", call.getString("call_id"))
                    .put("output", result))
            }
            if (round == 11) error("وصل الوكيل للحد الأقصى من خطوات الأدوات")
        }
        AgentResult(finalText, changed)
    }

    private data class Streamed(val output: JSONArray, val text: String)

    private fun streamResponse(accessToken: String, payload: JSONObject, onDelta: (String) -> Unit): Streamed {
        val req = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                val text = r.body?.string().orEmpty()
                error(apiError(r.code, text))
            }
            val source = r.body?.source() ?: error("OpenAI returned an empty stream")
            var completed: JSONObject? = null
            var failed: String? = null
            val text = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "response.output_text.delta" -> {
                        val delta = event.optString("delta")
                        if (delta.isNotEmpty()) { text.append(delta); onDelta(delta) }
                    }
                    "response.completed" -> completed = event.optJSONObject("response")
                    "response.failed" -> failed = event.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                        ?: event.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                        ?: "OpenAI response failed"
                    "response.incomplete" -> failed = "OpenAI response incomplete"
                    "error" -> failed = event.optString("message").ifBlank { "OpenAI stream error" }
                }
            }
            failed?.let { error(it) }
            val response = completed ?: error("انتهى بث OpenAI بدون response.completed")
            return Streamed(response.optJSONArray("output") ?: JSONArray(), text.toString())
        }
    }

    private fun workspaceTools(): JSONArray {
        fun noArgs(name: String, description: String) = JSONObject()
            .put("type", "function").put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()).put("additionalProperties", false))
            .put("strict", true)
        fun oneString(name: String, description: String, field: String, fieldDescription: String) = JSONObject()
            .put("type", "function").put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject().put(field, JSONObject().put("type", "string").put("description", fieldDescription)))
                .put("required", JSONArray().put(field)).put("additionalProperties", false))
            .put("strict", true)

        val write = JSONObject()
            .put("type", "function").put("name", "write_file")
            .put("description", "Create or replace a UTF-8 text file in the current workspace.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("path", JSONObject().put("type", "string").put("description", "Relative workspace path"))
                    .put("content", JSONObject().put("type", "string").put("description", "Complete new file contents")))
                .put("required", JSONArray().put("path").put("content")).put("additionalProperties", false))
            .put("strict", true)

        val namespace = JSONObject()
            .put("type", "namespace")
            .put("name", "workspace")
            .put("description", "Read and edit the user's local text-code workspace on the Android device.")
            .put("tools", JSONArray()
                .put(noArgs("list_files", "List every file path and UTF-8 character count in the workspace."))
                .put(oneString("read_file", "Read one text file from the workspace.", "path", "Relative workspace path"))
                .put(write)
                .put(oneString("delete_file", "Delete one file from the workspace.", "path", "Relative workspace path")))
        return JSONArray().put(namespace)
    }

    private fun executeWorkspaceTool(name: String, args: JSONObject, files: MutableMap<String, String>, markChanged: () -> Unit): String {
        fun path(): String {
            val p = args.optString("path").trim().replace('\\', '/')
            require(p.isNotBlank() && !p.startsWith('/') && !p.split('/').contains("..")) { "Invalid workspace path" }
            return p
        }
        return when (name) {
            "list_files" -> JSONArray(files.entries.sortedBy { it.key }.map { JSONObject().put("path", it.key).put("characters", it.value.length) }).toString()
            "read_file" -> {
                val p = path(); val content = files[p] ?: return JSONObject().put("ok", false).put("error", "not_found").put("path", p).toString()
                JSONObject().put("ok", true).put("path", p).put("content", content).toString()
            }
            "write_file" -> {
                val p = path(); val content = args.getString("content")
                if (files[p] != content) { files[p] = content; markChanged() }
                JSONObject().put("ok", true).put("path", p).put("characters", content.length).toString()
            }
            "delete_file" -> {
                val p = path(); val existed = files.remove(p) != null
                if (existed) markChanged()
                JSONObject().put("ok", true).put("path", p).put("deleted", existed).toString()
            }
            else -> JSONObject().put("ok", false).put("error", "unknown_tool").put("name", name).toString()
        }
    }

    private fun apiError(code: Int, body: String): String {
        val obj = runCatching { JSONObject(body) }.getOrNull()
        val error = obj?.optJSONObject("error")
        return error?.optString("message").takeUnless { it.isNullOrBlank() }
            ?: error?.optString("code").takeUnless { it.isNullOrBlank() }
            ?: "OpenAI HTTP $code"
    }
}
