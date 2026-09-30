package com.aiway.nativeapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AiWayViewModel(app: Application) : AndroidViewModel(app) {
    private val secure = SecureStore(app)
    private val projectStore = ProjectStore(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val githubClient = GitHubClient(http)
    private val openAiAuth = OpenAiAuthManager(app, secure, http)
    private val openAi = OpenAiDirectClient(http)

    val files = mutableStateMapOf<String, String>()
    val messages = mutableStateListOf<ChatMessage>()
    val repos = mutableStateListOf<GithubRepo>()
    val branches = mutableStateListOf<GithubBranch>()
    val openAiModels = mutableStateListOf<OpenAiDirectClient.Model>()

    var selectedFile by mutableStateOf<String?>(null)
    var githubToken by mutableStateOf(secure.get("github_token"))
    var githubUser by mutableStateOf("")
    var selectedRepo by mutableStateOf("")
    var selectedBranch by mutableStateOf("")

    var openAiAccount by mutableStateOf(openAiAuth.currentSession()?.email.orEmpty())
    var openAiStatus by mutableStateOf(if (openAiAuth.currentSession() != null) "مسجل الدخول" else "غير مسجل")
    var selectedModel by mutableStateOf(secure.get("openai_model"))
    var agentActivity by mutableStateOf("")
    var darkMode by mutableStateOf(secure.get("dark_mode") == "true")
    var toolRead by mutableStateOf(secure.get("tool_read").let { it.isBlank() || it == "true" })
    var toolWrite by mutableStateOf(secure.get("tool_write").let { it.isBlank() || it == "true" })
    var toolDelete by mutableStateOf(secure.get("tool_delete") == "true")
    var toolWebSearch by mutableStateOf(secure.get("tool_web_search") == "true")
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    init {
        files.putAll(projectStore.load())
        selectedFile = files.keys.firstOrNull()
        if (openAiAuth.currentSession() != null) refreshOpenAiModels()
    }

    fun updateFile(text: String) {
        selectedFile?.let { files[it] = text; persistFiles() }
    }

    fun addFile(name: String) {
        val clean = name.trim().replace('\\', '/').trim('/')
        if (clean.isBlank() || clean.startsWith(".") && clean.contains("..") || clean.split('/').contains("..")) {
            notice = "اسم الملف غير صالح"; return
        }
        if (!Regex("^[A-Za-z0-9._/-]+$").matches(clean)) { notice = "اسم الملف غير صالح"; return }
        if (files.containsKey(clean)) { notice = "الملف موجود"; return }
        files[clean] = ""
        selectedFile = clean
        persistFiles()
    }

    fun deleteSelectedFile() {
        selectedFile?.let { files.remove(it) }
        selectedFile = files.keys.firstOrNull()
        persistFiles()
    }

    private fun persistFiles() = projectStore.save(files.toMap())

    fun saveGithubToken(value: String) {
        githubToken = value.trim()
        if (githubToken.isBlank()) secure.remove("github_token") else secure.put("github_token", githubToken)
    }

    fun connectGithub() = viewModelScope.launch {
        if (githubToken.isBlank()) { notice = "أدخل GitHub token أولاً"; return@launch }
        busy = true
        runCatching {
            githubUser = githubClient.user(githubToken)
            repos.clear(); repos.addAll(githubClient.repos(githubToken))
            notice = "متصل بـ GitHub: $githubUser"
        }.onFailure { notice = it.message }
        busy = false
    }

    fun selectRepo(repo: GithubRepo) = viewModelScope.launch {
        selectedRepo = repo.fullName
        selectedBranch = repo.defaultBranch
        busy = true
        runCatching {
            branches.clear(); branches.addAll(githubClient.branches(githubToken, selectedRepo))
        }.onFailure { notice = it.message }
        busy = false
    }

    fun loadRepo() = viewModelScope.launch {
        if (selectedRepo.isBlank() || selectedBranch.isBlank()) { notice = "اختر المستودع والفرع"; return@launch }
        busy = true
        runCatching {
            val loaded = githubClient.loadTextFiles(githubToken, selectedRepo, selectedBranch)
            files.clear(); files.putAll(loaded)
            selectedFile = files.keys.firstOrNull()
            persistFiles()
            notice = "تم تحميل ${files.size} ملف"
        }.onFailure { notice = it.message }
        busy = false
    }

    fun pushRepo(message: String) = viewModelScope.launch {
        if (selectedRepo.isBlank() || selectedBranch.isBlank()) { notice = "اختر المستودع والفرع"; return@launch }
        busy = true
        runCatching {
            githubClient.pushFiles(githubToken, selectedRepo, selectedBranch, files.toMap(), message)
            notice = "تم Push إلى GitHub"
        }.onFailure { notice = it.message }
        busy = false
    }

    fun signInOpenAi(openBrowser: (String) -> Unit) = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        openAiStatus = "جارٍ تسجيل الدخول…"
        runCatching {
            val session = openAiAuth.signIn(openBrowser)
            openAiAccount = session.email.ifBlank { "ChatGPT" }
            openAiStatus = "مسجل الدخول"
            notice = "تم تسجيل الدخول إلى ChatGPT بدون Backend"
            refreshOpenAiModelsInternal()
        }.onFailure {
            openAiStatus = if (openAiAuth.currentSession() != null) "مسجل الدخول" else "غير مسجل"
            notice = it.message ?: "فشل تسجيل الدخول"
        }
        busy = false
    }

    fun logoutOpenAi() {
        openAiAuth.signOutLocal()
        openAiAccount = ""
        openAiStatus = "غير مسجل"
        openAiModels.clear()
        selectedModel = ""
        secure.remove("openai_model")
        notice = "تم تسجيل الخروج محلياً"
    }

    fun refreshOpenAiModels() = viewModelScope.launch {
        busy = true
        runCatching { refreshOpenAiModelsInternal() }.onFailure { notice = it.message }
        busy = false
    }

    private suspend fun refreshOpenAiModelsInternal() {
        val token = openAiAuth.validAccessToken()
        val models = openAi.listModels(token)
        openAiModels.clear(); openAiModels.addAll(models)
        if (models.isEmpty()) error("الحساب لم يرجع موديلات متاحة")
        if (selectedModel.isBlank() || models.none { it.slug == selectedModel }) {
            selectedModel = models.first().slug
            secure.put("openai_model", selectedModel)
        }
    }

    fun chooseModel(slug: String) {
        selectedModel = slug
        if (slug.isBlank()) secure.remove("openai_model") else secure.put("openai_model", slug)
    }

    fun setDarkMode(enabled: Boolean) {
        darkMode = enabled
        secure.put("dark_mode", enabled.toString())
    }

    fun setToolRead(enabled: Boolean) { toolRead = enabled; secure.put("tool_read", enabled.toString()) }
    fun setToolWrite(enabled: Boolean) { toolWrite = enabled; secure.put("tool_write", enabled.toString()) }
    fun setToolDelete(enabled: Boolean) { toolDelete = enabled; secure.put("tool_delete", enabled.toString()) }
    fun setToolWebSearch(enabled: Boolean) { toolWebSearch = enabled; secure.put("tool_web_search", enabled.toString()) }

    fun newChat() {
        messages.clear()
        agentActivity = ""
    }

    fun sendPrompt(prompt: String) = viewModelScope.launch {
        val text = prompt.trim()
        if (text.isBlank()) return@launch
        if (openAiAuth.currentSession() == null) { notice = "سجل الدخول إلى ChatGPT من الإعدادات أولاً"; return@launch }
        if (selectedModel.isBlank()) { notice = "اختر موديل من الإعدادات"; return@launch }

        messages.add(ChatMessage(role = "user", text = text))
        messages.add(ChatMessage(role = "assistant", text = ""))
        busy = true
        agentActivity = "جارٍ الاتصال بـ OpenAI…"
        val workspace = LinkedHashMap(files.toMap())
        runCatching {
            val token = openAiAuth.validAccessToken()
            openAi.runWorkspaceAgent(
                accessToken = token,
                model = selectedModel,
                files = workspace,
                prompt = text,
                tools = OpenAiDirectClient.ToolOptions(
                    allowRead = toolRead,
                    allowWrite = toolWrite,
                    allowDelete = toolDelete,
                    webSearch = toolWebSearch
                ),
                onTextDelta = { delta ->
                    viewModelScope.launch {
                        val idx = messages.indexOfLast { it.role == "assistant" }
                        if (idx >= 0) messages[idx] = messages[idx].copy(text = messages[idx].text + delta)
                    }
                },
                onTool = { tool -> viewModelScope.launch { agentActivity = "أداة: $tool" } }
            )
            files.clear(); files.putAll(workspace)
            if (selectedFile !in files.keys) selectedFile = files.keys.firstOrNull()
            persistFiles()
            val idx = messages.indexOfLast { it.role == "assistant" }
            if (idx >= 0 && messages[idx].text.isBlank()) messages[idx] = messages[idx].copy(text = "تم تنفيذ التعديلات على ملفات المشروع.")
        }.onFailure {
            val idx = messages.indexOfLast { it.role == "assistant" }
            if (idx >= 0) messages[idx] = messages[idx].copy(text = messages[idx].text + "\n\nخطأ: ${it.message}")
        }
        agentActivity = ""
        busy = false
    }
}
