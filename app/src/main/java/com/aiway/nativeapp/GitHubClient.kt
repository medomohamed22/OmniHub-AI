package com.aiway.nativeapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class GitHubClient(private val http: OkHttpClient = OkHttpClient()) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(token: String, path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val b = Request.Builder()
            .url("https://api.github.com$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "AiWay-Native")
        if (method != "GET") b.method(method, (body ?: JSONObject()).toString().toRequestBody(jsonType))
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException(if (msg.isNotBlank()) msg else "GitHub HTTP ${r.code}")
            }
            text
        }
    }

    suspend fun user(token: String): String = JSONObject(call(token, "/user")).optString("login")

    suspend fun repos(token: String): List<GithubRepo> {
        val arr = JSONArray(call(token, "/user/repos?sort=updated&per_page=100&affiliation=owner,collaborator,organization_member"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GithubRepo(o.getString("full_name"), o.optString("default_branch", "main"), o.optBoolean("private"))
        }
    }

    suspend fun branches(token: String, repo: String): List<GithubBranch> {
        val arr = JSONArray(call(token, "/repos/$repo/branches?per_page=100"))
        return (0 until arr.length()).map { GithubBranch(arr.getJSONObject(it).getString("name")) }
    }

    suspend fun loadTextFiles(token: String, repo: String, branch: String, maxFiles: Int = 120): LinkedHashMap<String, String> {
        val ref = JSONObject(call(token, "/repos/$repo/git/ref/heads/${java.net.URLEncoder.encode(branch, "UTF-8")}"))
        val head = ref.getJSONObject("object").getString("sha")
        val commit = JSONObject(call(token, "/repos/$repo/git/commits/$head"))
        val treeSha = commit.getJSONObject("tree").getString("sha")
        val tree = JSONObject(call(token, "/repos/$repo/git/trees/$treeSha?recursive=1")).getJSONArray("tree")
        val allowed = Regex("(?i)(^|/)(\\.gitignore|dockerfile|makefile)$|\\.(html?|css|scss|js|jsx|mjs|cjs|ts|tsx|json|md|txt|svg|py|java|kt|kts|xml|yml|yaml|toml|ini|sh|sql)$")
        val entries = mutableListOf<Pair<String,String>>()
        for (i in 0 until tree.length()) {
            val o = tree.getJSONObject(i)
            if (o.optString("type") == "blob" && o.optInt("size", 0) <= 1_500_000 && allowed.containsMatchIn(o.optString("path"))) {
                entries += o.getString("path") to o.getString("sha")
                if (entries.size >= maxFiles) break
            }
        }
        val out = linkedMapOf<String, String>()
        for ((path, sha) in entries) {
            val blob = JSONObject(call(token, "/repos/$repo/git/blobs/$sha"))
            if (blob.optString("encoding") != "base64") continue
            val raw = Base64.getMimeDecoder().decode(blob.optString("content").replace("\n", ""))
            if (raw.contains(0.toByte())) continue
            out[path] = raw.toString(Charsets.UTF_8)
        }
        return out
    }

    suspend fun pushFiles(token: String, repo: String, branch: String, files: Map<String,String>, message: String) {
        val encoded = java.net.URLEncoder.encode(branch, "UTF-8")
        val refObj = JSONObject(call(token, "/repos/$repo/git/ref/heads/$encoded"))
        val parentSha = refObj.getJSONObject("object").getString("sha")
        val parentCommit = JSONObject(call(token, "/repos/$repo/git/commits/$parentSha"))
        val baseTree = parentCommit.getJSONObject("tree").getString("sha")
        val treeItems = JSONArray()
        for ((path, content) in files) {
            val blobBody = JSONObject().put("content", Base64.getEncoder().encodeToString(content.toByteArray())).put("encoding", "base64")
            val blobSha = JSONObject(call(token, "/repos/$repo/git/blobs", "POST", blobBody)).getString("sha")
            val mode = if (path.endsWith(".sh") || path.substringAfterLast('/').startsWith("gradlew")) "100755" else "100644"
            treeItems.put(JSONObject().put("path", path).put("mode", mode).put("type", "blob").put("sha", blobSha))
        }
        val treeBody = JSONObject().put("base_tree", baseTree).put("tree", treeItems)
        val treeSha = JSONObject(call(token, "/repos/$repo/git/trees", "POST", treeBody)).getString("sha")
        val commitBody = JSONObject().put("message", message.ifBlank { "Update from AiWay Native" }).put("tree", treeSha).put("parents", JSONArray().put(parentSha))
        val newCommit = JSONObject(call(token, "/repos/$repo/git/commits", "POST", commitBody)).getString("sha")
        call(token, "/repos/$repo/git/refs/heads/$encoded", "PATCH", JSONObject().put("sha", newCommit).put("force", false))
    }
}
