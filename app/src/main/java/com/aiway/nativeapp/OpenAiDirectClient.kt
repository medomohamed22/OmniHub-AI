package com.aiway.nativeapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiDirectClient(private val http: OkHttpClient) {
    data class Model(val slug: String, val displayName: String)
    data class AgentResult(val finalText: String, val changed: Boolean)
    data class ToolOptions(
        val allowRead: Boolean = true,
        val allowWrite: Boolean = true,
        val allowDelete: Boolean = false,
        val webSearch: Boolean = false
    )

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
        tools: ToolOptions,
        history: List<Pair<String, String>> = emptyList(),
        mode: String = "build",
        rules: String = "",
        onTextDelta: (String) -> Unit = {},
        onTool: (String) -> Unit = {}
    ): AgentResult = withContext(Dispatchers.IO) {
        require(model.isNotBlank()) { "اختر موديل أولاً" }
        val toolDescription = buildList {
            if (tools.allowRead) add("قراءة الملفات")
            if (tools.allowWrite) add("إنشاء/تعديل الملفات")
            if (tools.allowDelete) add("حذف الملفات")
            if (tools.webSearch) add("البحث في الويب")
        }.joinToString("، ").ifBlank { "بدون أدوات" }

        val input = JSONArray()
        history.takeLast(20).forEach { (role, text) ->
            if (text.isNotBlank() && (role == "user" || role == "assistant")) {
                input.put(JSONObject().put("role", role).put("content", text.take(6000)))
            }
        }
        input.put(
            JSONObject()
                .put("role", "user")
                .put("content", "طلب المستخدم:\n$prompt\n\nمساحة العمل الحالية تحتوي ${files.size} ملف. الأدوات المسموح بها: $toolDescription. لا تفترض محتوى ملف قبل قراءته.")
        )
        val modeText = if (mode == "plan")
            " أنت الآن في وضع التخطيط: اقرأ وابحث فقط ولا تعدّل أو تحذف أي ملف. قدّم خطة مرقّمة واضحة بالملفات والخطوات، وانتظر موافقة المستخدم قبل التنفيذ."
        else
            " أنت في وضع البناء: نفّذ التعديلات مباشرة. فضّل edit_file للتعديلات الصغيرة بدل إعادة كتابة الملف كاملاً، واستخدم search_files لتحديد المواقع قبل القراءة."
        val rulesText = if (rules.isNotBlank()) "\n\nقواعد المشروع (AGENTS.md):\n" + rules.take(6000) else ""
        val instructions = "أنت AiWay، وكيل برمجة داخل تطبيق Android Native. نفّذ طلب المستخدم بدقة. استخدم أدوات workspace فقط حسب الصلاحيات المتاحة، اقرأ قبل التعديل، غيّر أقل قدر لازم، حافظ على قابلية البناء، ولا تحذف ملفات إلا عند الحاجة ومع وجود أداة الحذف. بعد الانتهاء اشرح باختصار ما فعلته بالعربية." + modeText + rulesText
        var changed = false
        var finalText = ""

        repeat(12) { round ->
            val payload = JSONObject()
                .put("model", model)
                .put("instructions", instructions)
                .put("input", input)
                .put("parallel_tool_calls", false)
                .put("store", false)
                .put("stream", true)

            val availableTools = toolsJson(tools)
            if (availableTools.length() > 0) payload.put("tools", availableTools)

            val response = streamResponse(accessToken, payload, onTextDelta)
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
                val result = executeWorkspaceTool(name, args, files, tools) { changed = true }
                input.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", call.getString("call_id"))
                        .put("output", result)
                )
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

    private fun toolsJson(options: ToolOptions): JSONArray {
        val all = JSONArray()
        val workspaceTools = JSONArray()

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

        fun fn(name: String, description: String, vararg fields: Pair<String, String>): JSONObject {
            val props = JSONObject()
            val req = JSONArray()
            fields.forEach { (f, d) -> props.put(f, JSONObject().put("type", "string").put("description", d)); req.put(f) }
            return JSONObject().put("type", "function").put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req).put("additionalProperties", false))
                .put("strict", true)
        }

        if (options.allowRead) {
            workspaceTools.put(fn("search_files", "Case-insensitive text search across all workspace files. Returns path, line number and line text.", "query" to "Text to search for"))
            workspaceTools.put(noArgs("list_files", "List every file path and UTF-8 character count in the workspace."))
            workspaceTools.put(oneString("read_file", "Read one UTF-8 text file from the workspace.", "path", "Relative workspace path"))
        }
        if (options.allowWrite) {
            workspaceTools.put(
                JSONObject()
                    .put("type", "function").put("name", "write_file")
                    .put("description", "Create or replace a UTF-8 text file in the current workspace.")
                    .put("parameters", JSONObject().put("type", "object")
                        .put("properties", JSONObject()
                            .put("path", JSONObject().put("type", "string").put("description", "Relative workspace path"))
                            .put("content", JSONObject().put("type", "string").put("description", "Complete new file contents")))
                        .put("required", JSONArray().put("path").put("content")).put("additionalProperties", false))
                    .put("strict", true)
            )
        }
        if (options.allowWrite) {
            workspaceTools.put(fn("edit_file", "Replace exactly one occurrence of old_str with new_str in a file. Fails if old_str is missing or not unique. Prefer this over write_file for small edits.",
                "path" to "Relative workspace path", "old_str" to "Exact existing text (must appear once)", "new_str" to "Replacement text"))
            workspaceTools.put(fn("move_file", "Move or rename a file inside the workspace.", "from" to "Existing relative path", "to" to "New relative path"))
        }
        if (options.allowDelete) {
            workspaceTools.put(oneString("delete_file", "Delete one file from the workspace.", "path", "Relative workspace path"))
        }
        if (workspaceTools.length() > 0) {
            all.put(
                JSONObject()
                    .put("type", "namespace")
                    .put("name", "workspace")
                    .put("description", "Read and edit the user's local text-code workspace on the Android device.")
                    .put("tools", workspaceTools)
            )
        }
        if (options.webSearch) all.put(JSONObject().put("type", "web_search"))
        return all
    }

    private fun executeWorkspaceTool(
        name: String,
        args: JSONObject,
        files: MutableMap<String, String>,
        options: ToolOptions,
        markChanged: () -> Unit
    ): String {
        fun path(): String {
            val p = args.optString("path").trim().replace('\\', '/')
            require(p.isNotBlank() && !p.startsWith('/') && !p.split('/').contains("..")) { "Invalid workspace path" }
            return p
        }
        return when (name) {
            "list_files" -> if (!options.allowRead) denied(name) else JSONArray(files.entries.sortedBy { it.key }.map { JSONObject().put("path", it.key).put("characters", it.value.length) }).toString()
            "read_file" -> if (!options.allowRead) denied(name) else {
                val p = path(); val content = files[p] ?: return JSONObject().put("ok", false).put("error", "not_found").put("path", p).toString()
                JSONObject().put("ok", true).put("path", p).put("content", content).toString()
            }
            "write_file" -> if (!options.allowWrite) denied(name) else {
                val p = path(); val content = args.getString("content")
                if (files[p] != content) { files[p] = content; markChanged() }
                JSONObject().put("ok", true).put("path", p).put("characters", content.length).toString()
            }
            "search_files" -> if (!options.allowRead) denied(name) else {
                val q = args.optString("query").trim()
                require(q.isNotBlank()) { "query is empty" }
                val hits = JSONArray()
                outer@ for ((p, c) in files.entries.sortedBy { it.key }) {
                    val ls = c.split('\n')
                    for (i in ls.indices) {
                        if (ls[i].contains(q, ignoreCase = true)) {
                            hits.put(JSONObject().put("path", p).put("line", i + 1).put("text", ls[i].trim().take(200)))
                            if (hits.length() >= 60) break@outer
                        }
                    }
                }
                JSONObject().put("ok", true).put("matches", hits).put("truncated", hits.length() >= 60).toString()
            }
            "edit_file" -> if (!options.allowWrite) denied(name) else {
                val p = path()
                val oldText = args.getString("old_str")
                val replacement = args.getString("new_str")
                val content = files[p] ?: return JSONObject().put("ok", false).put("error", "not_found").put("path", p).toString()
                val first = if (oldText.isEmpty()) -1 else content.indexOf(oldText)
                when {
                    first < 0 -> JSONObject().put("ok", false).put("error", "old_str_not_found").toString()
                    content.indexOf(oldText, first + oldText.length) >= 0 -> JSONObject().put("ok", false).put("error", "old_str_not_unique").toString()
                    else -> {
                        files[p] = content.substring(0, first) + replacement + content.substring(first + oldText.length)
                        markChanged()
                        JSONObject().put("ok", true).put("path", p).toString()
                    }
                }
            }
            "move_file" -> if (!options.allowWrite) denied(name) else {
                fun norm(k: String): String {
                    val v = args.optString(k).trim().replace('\\', '/')
                    require(v.isNotBlank() && !v.startsWith('/') && !v.split('/').contains("..")) { "Invalid workspace path" }
                    return v
                }
                val from = norm("from")
                val to = norm("to")
                val c = files[from] ?: return JSONObject().put("ok", false).put("error", "not_found").put("path", from).toString()
                if (files.containsKey(to)) return JSONObject().put("ok", false).put("error", "destination_exists").toString()
                files.remove(from); files[to] = c; markChanged()
                JSONObject().put("ok", true).put("from", from).put("to", to).toString()
            }
            "delete_file" -> if (!options.allowDelete) denied(name) else {
                val p = path(); val existed = files.remove(p) != null
                if (existed) markChanged()
                JSONObject().put("ok", true).put("path", p).put("deleted", existed).toString()
            }
            else -> JSONObject().put("ok", false).put("error", "unknown_tool").put("name", name).toString()
        }
    }

    private fun denied(name: String) = JSONObject().put("ok", false).put("error", "tool_disabled").put("name", name).toString()

    private fun apiError(code: Int, body: String): String {
        val obj = runCatching { JSONObject(body) }.getOrNull()
        val error = obj?.optJSONObject("error")
        val errorCode = error?.optString("code").orEmpty()
        if (errorCode == "subscription_sharing_usage_limit_exceeded") return "وصلت إلى حد استخدام خطة ChatGPT. افتح الإعدادات ← الاستخدام لمعرفة موعد إعادة التعيين."
        if (errorCode == "subscription_sharing_usage_unavailable") return "استخدام خطة ChatGPT غير متاح حالياً لهذا الطلب أو الحساب."
        return error?.optString("message").takeUnless { it.isNullOrBlank() }
            ?: errorCode.takeUnless { it.isBlank() }
            ?: "OpenAI HTTP $code"
    }
}
