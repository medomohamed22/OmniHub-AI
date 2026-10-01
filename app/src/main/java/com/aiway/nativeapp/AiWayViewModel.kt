package com.aiway.nativeapp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.concurrent.Executors

class AiWayViewModel(app: Application) : AndroidViewModel(app) {
    private val secure = SecureStore(app)
    private val projectStore = ProjectStore(app)
    private val db = LocalDb(app)
    private val dbDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "aiway-db").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val http = NetworkModule.buildClient(app)
    private val githubClient = GitHubClient(http)
    private val openAiAuth = OpenAiAuthManager(app, secure, http)
    private val openAi = OpenAiDirectClient(http)

    val files = mutableStateMapOf<String, String>()
    val messages = mutableStateListOf<ChatMessage>()
    val conversations = mutableStateListOf<ConversationInfo>()
    val repos = mutableStateListOf<GithubRepo>()
    val branches = mutableStateListOf<GithubBranch>()
    val openAiModels = mutableStateListOf<OpenAiDirectClient.Model>()
    val diagnostics = mutableStateListOf<NetworkModule.Check>()
    val events = mutableStateListOf<Triple<Long, String, String>>()

    var currentConvId by mutableStateOf<String?>(null)
    var selectedFile by mutableStateOf<String?>(null)
    var githubToken by mutableStateOf(secure.get("github_token"))
    var githubUser by mutableStateOf("")
    var selectedRepo by mutableStateOf(pref("gh_repo"))
    var selectedBranch by mutableStateOf(pref("gh_branch"))

    var openAiAccount by mutableStateOf(openAiAuth.currentSession()?.email ?: "")
    var openAiStatus by mutableStateOf(if (openAiAuth.currentSession() != null) "مسجل الدخول" else "غير مسجل")
    var selectedModel by mutableStateOf(pref("openai_model"))
    var agentActivity by mutableStateOf("")
    var darkMode by mutableStateOf(pref("dark_mode") == "true")
    var toolRead by mutableStateOf(pref("tool_read").let { it.isBlank() || it == "true" })
    var toolWrite by mutableStateOf(pref("tool_write").let { it.isBlank() || it == "true" })
    var toolDelete by mutableStateOf(pref("tool_delete") == "true")
    var toolWebSearch by mutableStateOf(pref("tool_web_search") == "true")
    var agentMode by mutableStateOf(pref("agent_mode").ifBlank { "build" })
    var lastRun by mutableStateOf<RunChanges?>(null)
    var busy by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    var storageInfo by mutableStateOf("")

    private var persistJob: Job? = null
    private var lastFileLog = 0L
    private var lastMsgPersist = 0L

    /** Settings live in the local database; legacy values in SecureStore are read once as a fallback. */
    private fun pref(key: String): String = db.getKv(key) ?: secure.get(key)

    private fun setPref(key: String, value: String, log: Boolean = true) {
        db.setKv(key, value)
        if (log) db.log("setting", "$key=$value")
    }

    init {
        // workspace: DB is the source of truth; import the legacy workspace.json once.
        if (!db.hasFiles()) db.saveFiles(projectStore.load())
        files.putAll(db.loadFiles())
        selectedFile = files.keys.firstOrNull()

        // restore last open conversation
        val last = db.getKv("current_conv").orEmpty()
        if (last.isNotBlank()) {
            val saved = db.loadMessages(last)
            if (saved.isNotEmpty()) { currentConvId = last; messages.addAll(saved) }
        }
        refreshConversations()
        refreshStorageInfo()
        db.log("app_start")
        if (openAiAuth.currentSession() != null) refreshOpenAiModels()
    }

    // ---------------- storage helpers ----------------
    private fun dbo(block: () -> Unit) {
        viewModelScope.launch { withContext(dbDispatcher) { runCatching(block) } }
    }

    fun refreshConversations() {
        conversations.clear(); conversations.addAll(db.listConversations())
    }

    fun refreshStorageInfo() {
        val kb = db.sizeBytes(getApplication<Application>()) / 1024
        storageInfo = "${conversations.size} محادثة • ${db.messageCount()} رسالة • ${db.versionCount()} نسخة ملف • ${kb} KB"
    }

    fun loadEvents() { events.clear(); events.addAll(db.recentEvents(60)) }

    fun clearAllConversations() {
        db.clearConversations(); db.log("conversations_cleared")
        messages.clear(); currentConvId = null; db.setKv("current_conv", "")
        refreshConversations(); refreshStorageInfo()
        notice = "تم مسح كل المحادثات"
    }

    /** Synchronous flush, called from Activity.onStop so nothing is lost if Android kills the process. */
    fun flush() {
        persistJob?.cancel()
        runCatching { db.saveFiles(files.toMap()) }
        currentConvId?.let { id -> messages.forEach { if (it.text.isNotBlank() || it.role == "user") db.saveMessage(id, it) } }
    }

    // ---------------- workspace ----------------
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
        dbo { db.log("file_add", clean) }
        persistFiles(immediate = true)
    }

    fun deleteSelectedFile() {
        val gone = selectedFile
        selectedFile?.let { files.remove(it) }
        selectedFile = files.keys.firstOrNull()
        dbo { db.log("file_delete", gone.orEmpty()) }
        persistFiles(immediate = true)
    }

    private fun persistFiles(immediate: Boolean = false) {
        persistJob?.cancel()
        val changed = selectedFile.orEmpty()
        persistJob = viewModelScope.launch {
            if (!immediate) delay(700)
            val snapshot = files.toMap()
            withContext(dbDispatcher) {
                runCatching {
                    db.saveFiles(snapshot)
                    val now = System.currentTimeMillis()
                    if (now - lastFileLog > 30_000) { db.log("file_edit", changed); lastFileLog = now }
                }
            }
        }
    }

    // ---------------- github ----------------
    fun saveGithubToken(value: String) {
        githubToken = value.trim()
        if (githubToken.isBlank()) secure.remove("github_token") else secure.put("github_token", githubToken)
        dbo { db.log("github_token", if (githubToken.isBlank()) "removed" else "saved") }
    }

    fun connectGithub() = viewModelScope.launch {
        if (githubToken.isBlank()) { notice = "أدخل GitHub token أولاً"; return@launch }
        busy = true
        runCatching {
            githubUser = githubClient.user(githubToken)
            repos.clear(); repos.addAll(githubClient.repos(githubToken))
            notice = "متصل بـ GitHub: $githubUser"
            db.log("github_connect", githubUser)
        }.onFailure { notice = NetworkModule.friendlyError(it) }
        busy = false
    }

    fun selectRepo(repo: GithubRepo) = viewModelScope.launch {
        selectedRepo = repo.fullName
        selectedBranch = repo.defaultBranch
        setPref("gh_repo", selectedRepo); setPref("gh_branch", selectedBranch, log = false)
        busy = true
        runCatching {
            branches.clear(); branches.addAll(githubClient.branches(githubToken, selectedRepo))
        }.onFailure { notice = NetworkModule.friendlyError(it) }
        busy = false
    }

    fun loadRepo() = viewModelScope.launch {
        if (selectedRepo.isBlank() || selectedBranch.isBlank()) { notice = "اختر المستودع والفرع"; return@launch }
        busy = true
        runCatching {
            val loaded = githubClient.loadTextFiles(githubToken, selectedRepo, selectedBranch)
            files.clear(); files.putAll(loaded)
            selectedFile = files.keys.firstOrNull()
            persistFiles(immediate = true)
            db.log("repo_loaded", "$selectedRepo@$selectedBranch (${files.size})")
            notice = "تم تحميل ${files.size} ملف"
        }.onFailure { notice = NetworkModule.friendlyError(it) }
        busy = false
    }

    fun pushRepo(message: String) = viewModelScope.launch {
        if (selectedRepo.isBlank() || selectedBranch.isBlank()) { notice = "اختر المستودع والفرع"; return@launch }
        busy = true
        runCatching {
            githubClient.pushFiles(githubToken, selectedRepo, selectedBranch, files.toMap(), message)
            db.log("repo_push", "$selectedRepo@$selectedBranch")
            notice = "تم Push إلى GitHub"
        }.onFailure { notice = NetworkModule.friendlyError(it) }
        busy = false
    }

    // ---------------- ChatGPT login ----------------
    fun signInOpenAi(openBrowser: (String) -> Unit) = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        openAiStatus = "جارٍ تسجيل الدخول…"
        withContext(dbDispatcher) { db.log("login_start") }
        runCatching {
            val session = openAiAuth.signIn(openBrowser)
            openAiAccount = session.email.ifBlank { "ChatGPT" }
            openAiStatus = "مسجل الدخول"
            withContext(dbDispatcher) {
                db.setKv("last_login_email", session.email)
                db.setKv("last_login_at", System.currentTimeMillis().toString())
                db.log("login_ok", session.email)
            }
            notice = "تم تسجيل الدخول إلى ChatGPT بنجاح"
            refreshOpenAiModelsInternal()
        }.onFailure {
            openAiStatus = if (openAiAuth.currentSession() != null) "مسجل الدخول" else "غير مسجل"
            val msg = NetworkModule.friendlyError(it)
            notice = msg
            withContext(dbDispatcher) { db.log("login_fail", msg) }
        }
        busy = false
    }

    fun logoutOpenAi() {
        openAiAuth.signOutLocal()
        openAiAccount = ""
        openAiStatus = "غير مسجل"
        openAiModels.clear()
        selectedModel = ""
        setPref("openai_model", "", log = false)
        secure.remove("openai_model")
        dbo { db.log("logout") }
        notice = "تم تسجيل الخروج محلياً"
    }

    fun refreshOpenAiModels() = viewModelScope.launch {
        busy = true
        runCatching { refreshOpenAiModelsInternal() }.onFailure { notice = NetworkModule.friendlyError(it) }
        busy = false
    }

    private suspend fun refreshOpenAiModelsInternal() {
        val token = openAiAuth.validAccessToken()
        val models = openAi.listModels(token)
        openAiModels.clear(); openAiModels.addAll(models)
        if (models.isEmpty()) error("الحساب لم يرجع موديلات متاحة")
        if (selectedModel.isBlank() || models.none { it.slug == selectedModel }) {
            selectedModel = models.first().slug
            setPref("openai_model", selectedModel)
        }
    }

    fun chooseModel(slug: String) {
        selectedModel = slug
        setPref("openai_model", slug)
    }

    fun runDiagnostics() = viewModelScope.launch {
        if (checking) return@launch
        checking = true
        diagnostics.clear()
        val result = withContext(kotlinx.coroutines.Dispatchers.IO) { NetworkModule.diagnose(http) }
        diagnostics.addAll(result)
        checking = false
    }

    // ---------------- settings ----------------
    fun updateDarkMode(enabled: Boolean) { darkMode = enabled; setPref("dark_mode", enabled.toString()) }
    fun updateAgentMode(mode: String) { agentMode = mode; setPref("agent_mode", mode) }
    fun updateToolRead(enabled: Boolean) { toolRead = enabled; setPref("tool_read", enabled.toString()) }
    fun updateToolWrite(enabled: Boolean) { toolWrite = enabled; setPref("tool_write", enabled.toString()) }
    fun updateToolDelete(enabled: Boolean) { toolDelete = enabled; setPref("tool_delete", enabled.toString()) }
    fun updateToolWebSearch(enabled: Boolean) { toolWebSearch = enabled; setPref("tool_web_search", enabled.toString()) }

    // ---------------- review / undo ----------------
    fun undoLastRun() {
        val run = lastRun ?: return
        files.clear(); files.putAll(run.before)
        if (selectedFile !in files.keys) selectedFile = files.keys.firstOrNull()
        persistFiles(immediate = true)
        dbo { db.log("undo_run", "${run.changes.size} files") }
        lastRun = null
        notice = "تم التراجع عن كل التعديلات"
    }

    fun revertFile(path: String) {
        val run = lastRun ?: return
        val old = run.before[path]
        if (old == null) files.remove(path) else files[path] = old
        if (selectedFile !in files.keys) selectedFile = files.keys.firstOrNull()
        persistFiles(immediate = true)
        val now = files.toMap()
        val ch = DiffUtil.changes(run.before, now)
        lastRun = if (ch.isEmpty()) null else run.copy(after = now, changes = ch)
        notice = "تم التراجع عن $path"
    }

    fun dismissRun() { lastRun = null }

    // ---------------- AGENTS.md ----------------
    fun createAgentsFile() {
        val existing = files.keys.firstOrNull { it.equals("AGENTS.md", ignoreCase = true) }
        if (existing != null) { selectedFile = existing; notice = "AGENTS.md موجود بالفعل"; return }
        files["AGENTS.md"] = "# قواعد المشروع\n\n- اللغة والإطار: \n- أسلوب الكود: \n- أوامر البناء والاختبار: \n- ممنوع: \n"
        selectedFile = "AGENTS.md"
        persistFiles(immediate = true)
        notice = "تم إنشاء AGENTS.md — سيقرأه الوكيل تلقائياً في كل طلب"
    }

    // ---------------- live preview ----------------
    fun previewHtml(): String? {
        val entry = files.keys.firstOrNull { it.equals("index.html", true) }
            ?: files.keys.firstOrNull { it.endsWith(".html", true) } ?: return null
        val dir = entry.substringBeforeLast('/', "")
        fun resolve(ref: String): String? {
            if (ref.startsWith("http") || ref.startsWith("//") || ref.startsWith("data:")) return null
            val clean = ref.substringBefore('?').substringBefore('#').removePrefix("./")
            val full = if (clean.startsWith("/")) clean.trimStart('/') else if (dir.isEmpty()) clean else "$dir/$clean"
            return files[full]
        }
        var html = files[entry].orEmpty()
        html = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).replace(html) { m ->
            val href = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)
            if (href != null && href.substringBefore('?').endsWith(".css", true)) {
                resolve(href)?.let { "<style>\n$it\n</style>" } ?: m.value
            } else m.value
        }
        html = Regex("<script\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*>\\s*</script>", RegexOption.IGNORE_CASE).replace(html) { m ->
            resolve(m.groupValues[1])?.let { "<script>\n" + it.replace("</script", "<\\/script") + "\n</script>" } ?: m.value
        }
        return html
    }

    // ---------------- ZIP export / import ----------------
    fun exportZip(uri: Uri) = viewModelScope.launch {
        val snapshot = files.toMap()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { os ->
                    ZipOutputStream(os.buffered()).use { z ->
                        snapshot.forEach { (p, c) ->
                            z.putNextEntry(ZipEntry(p))
                            z.write(c.toByteArray(Charsets.UTF_8))
                            z.closeEntry()
                        }
                    }
                }
            }
        }
        result.onSuccess { notice = "تم تصدير ${snapshot.size} ملف"; dbo { db.log("export_zip", "${snapshot.size} files") } }
            .onFailure { notice = "فشل التصدير: ${it.message}" }
    }

    fun importZip(uri: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { readZip(uri) } }
        result.onSuccess { loaded ->
            if (loaded.isEmpty()) { notice = "لا توجد ملفات نصية صالحة في الأرشيف"; return@onSuccess }
            val before = files.toMap()
            files.putAll(loaded)
            if (selectedFile == null) selectedFile = files.keys.firstOrNull()
            persistFiles(immediate = true)
            val now = files.toMap()
            val ch = DiffUtil.changes(before, now)
            lastRun = if (ch.isEmpty()) null else RunChanges(before, now, ch)
            dbo { db.log("import_zip", "${loaded.size} files") }
            notice = "تم استيراد ${loaded.size} ملف (يمكنك التراجع من المحادثة)"
        }.onFailure { notice = "فشل الاستيراد: ${it.message}" }
    }

    private fun readZip(uri: Uri): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var total = 0L
        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { ins ->
            ZipInputStream(ins.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/').trimStart('/')
                    val parts = name.split('/')
                    if (name.isBlank() || parts.contains("..") || parts.contains(".git") || parts.contains("node_modules")) continue
                    val bytes = readCapped(z, 1_000_000) ?: continue
                    if (bytes.any { it.toInt() == 0 }) continue
                    total += bytes.size
                    if (total > 8_000_000L || out.size >= 800) break
                    out[name] = String(bytes, Charsets.UTF_8)
                }
            }
        }
        return out
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            bo.write(buf, 0, n)
        }
        return bo.toByteArray()
    }

    // ---------------- conversations ----------------
    fun newChat() {
        if (busy) return
        flush()
        messages.clear()
        currentConvId = null
        agentActivity = ""
        db.setKv("current_conv", "")
    }

    fun openConversation(id: String) {
        if (busy || id == currentConvId) return
        flush()
        val saved = db.loadMessages(id)
        messages.clear(); messages.addAll(saved)
        currentConvId = id
        db.setKv("current_conv", id)
    }

    fun deleteConversation(id: String) {
        if (busy && id == currentConvId) return
        db.deleteConversation(id)
        db.log("conversation_deleted", id)
        if (id == currentConvId) { messages.clear(); currentConvId = null; db.setKv("current_conv", "") }
        refreshConversations(); refreshStorageInfo()
    }

    fun renameConversation(id: String, title: String) {
        val t = title.trim().take(60)
        if (t.isBlank()) return
        db.renameConversation(id, t)
        refreshConversations()
    }

    private fun ensureConversation(firstText: String): String {
        currentConvId?.let { return it }
        val id = UUID.randomUUID().toString()
        currentConvId = id
        db.upsertConversation(id, firstText.replace('\n', ' ').take(48))
        db.setKv("current_conv", id)
        return id
    }

    private fun persistMessage(convId: String, m: ChatMessage) = dbo { db.saveMessage(convId, m) }

    fun sendPrompt(prompt: String) = viewModelScope.launch {
        val text = prompt.trim()
        if (text.isBlank() || busy) return@launch
        if (openAiAuth.currentSession() == null) { notice = "سجل الدخول إلى ChatGPT من الإعدادات أولاً"; return@launch }
        if (selectedModel.isBlank()) { notice = "اختر موديل من الإعدادات"; return@launch }

        val history = messages.filter { it.text.isNotBlank() }.map { it.role to it.text }
        val convId = ensureConversation(text)
        val userMsg = ChatMessage(role = "user", text = text)
        val botMsg = ChatMessage(role = "assistant", text = "")
        messages.add(userMsg)
        messages.add(botMsg)
        persistMessage(convId, userMsg)
        refreshConversations()

        busy = true
        agentActivity = "جارٍ الاتصال بـ OpenAI…"
        val before = files.toMap()
        lastRun = null
        val workspace = LinkedHashMap(before)
        val planMode = agentMode == "plan"
        val rules = files.entries.firstOrNull { it.key.equals("AGENTS.md", ignoreCase = true) }?.value.orEmpty()

        fun updateBot(transform: (String) -> String) {
            val idx = messages.indexOfFirst { it.id == botMsg.id }
            if (idx >= 0) messages[idx] = messages[idx].copy(text = transform(messages[idx].text))
        }

        runCatching {
            val token = openAiAuth.validAccessToken()
            openAi.runWorkspaceAgent(
                accessToken = token,
                model = selectedModel,
                files = workspace,
                prompt = text,
                history = history,
                mode = agentMode,
                rules = rules,
                tools = OpenAiDirectClient.ToolOptions(
                    allowRead = toolRead,
                    allowWrite = toolWrite && !planMode,
                    allowDelete = toolDelete && !planMode,
                    webSearch = toolWebSearch
                ),
                onTextDelta = { delta ->
                    viewModelScope.launch {
                        updateBot { it + delta }
                        val now = System.currentTimeMillis()
                        if (now - lastMsgPersist > 1200) {
                            lastMsgPersist = now
                            messages.firstOrNull { it.id == botMsg.id }?.let { persistMessage(convId, it) }
                        }
                    }
                },
                onTool = { tool -> viewModelScope.launch { agentActivity = "أداة: $tool" } }
            )
            files.clear(); files.putAll(workspace)
            if (selectedFile !in files.keys) selectedFile = files.keys.firstOrNull()
            persistFiles(immediate = true)
            val ch = DiffUtil.changes(before, files.toMap())
            if (ch.isNotEmpty()) {
                lastRun = RunChanges(before, files.toMap(), ch)
                dbo { db.log("agent_run", "${ch.size} files, model=$selectedModel") }
            }
            updateBot { if (it.isBlank()) "تم تنفيذ التعديلات على ملفات المشروع." else it }
        }.onFailure {
            updateBot { old -> old + "\n\nخطأ: ${NetworkModule.friendlyError(it)}" }
        }
        messages.firstOrNull { it.id == botMsg.id }?.let { persistMessage(convId, it) }
        agentActivity = ""
        busy = false
        refreshConversations()
        refreshStorageInfo()
    }

    override fun onCleared() {
        runCatching { flush() }
        super.onCleared()
    }
}
