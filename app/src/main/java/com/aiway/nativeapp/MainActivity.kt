package com.aiway.nativeapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AiWayApp() }
    }
}

enum class AppTab(val label: String) { Chat("المحادثة"), Files("الملفات"), Github("GitHub"), Settings("الإعدادات") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiWayApp(vm: AiWayViewModel = viewModel()) {
    var tab by remember { mutableStateOf(AppTab.Chat) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) {
        val n = vm.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(n)
        vm.notice = null
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = { Text("AiWay Native") },
                        actions = {
                            if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = {
                                    Icon(
                                        when (item) {
                                            AppTab.Chat -> Icons.Default.Chat
                                            AppTab.Files -> Icons.Default.Code
                                            AppTab.Github -> Icons.Default.Cloud
                                            AppTab.Settings -> Icons.Default.Settings
                                        }, null
                                    )
                                },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        AppTab.Chat -> ChatScreen(vm)
                        AppTab.Files -> FilesScreen(vm)
                        AppTab.Github -> GithubScreen(vm)
                        AppTab.Settings -> SettingsScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: AiWayViewModel) {
    var prompt by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = {}, label = { Text("ChatGPT: ${vm.openAiStatus}") })
            if (vm.selectedModel.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = {}, label = { Text(vm.selectedModel) })
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.messages.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("مساعد برمجي Native", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text("اكتب طلبك وسيعمل الوكيل مباشرة عبر OpenAI Responses API. لا يوجد WebView ولا Backend خارجي؛ قراءة وتعديل الملفات تتم محلياً على الهاتف.")
                        }
                    }
                }
            }
            items(vm.messages, key = { it.id }) { m ->
                val bg = if (m.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.background(bg).padding(12.dp).fillMaxWidth()) {
                        Text(if (m.role == "user") "أنت" else "AiWay", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer { Text(m.text.ifBlank { "…" }) }
                    }
                }
            }
        }
        if (vm.agentActivity.isNotBlank()) {
            Text(vm.agentActivity, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 5,
                placeholder = { Text("اطلب تعديل المشروع…") }
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { val p = prompt; prompt = ""; vm.sendPrompt(p) },
                enabled = prompt.isNotBlank() && !vm.busy
            ) { Icon(Icons.Default.Send, "إرسال") }
        }
    }
}

@Composable
private fun FilesScreen(vm: AiWayViewModel) {
    var newName by remember { mutableStateOf("") }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(130.dp).fillMaxHeight().padding(8.dp)) {
            Text("الملفات", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(vm.files.keys.toList()) { name ->
                    val selected = vm.selectedFile == name
                    Surface(
                        tonalElevation = if (selected) 4.dp else 0.dp,
                        modifier = Modifier.fillMaxWidth().clickable { vm.selectedFile = name }
                    ) { Text(name, Modifier.padding(8.dp), fontSize = 12.sp, maxLines = 2) }
                }
            }
            OutlinedTextField(newName, { newName = it }, label = { Text("ملف جديد") }, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 11.sp))
            Row {
                IconButton(onClick = { vm.addFile(newName); newName = "" }) { Icon(Icons.Default.Add, "إضافة") }
                IconButton(onClick = { vm.deleteSelectedFile() }, enabled = vm.selectedFile != null) { Icon(Icons.Default.Delete, "حذف") }
            }
        }
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
            Text(vm.selectedFile ?: "لا يوجد ملف", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = vm.selectedFile?.let { vm.files[it] }.orEmpty(),
                    onValueChange = vm::updateFile,
                    modifier = Modifier.fillMaxSize(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    enabled = vm.selectedFile != null
                )
            }
        }
    }
}

@Composable
private fun GithubScreen(vm: AiWayViewModel) {
    var token by remember(vm.githubToken) { mutableStateOf(vm.githubToken) }
    var commitMessage by remember { mutableStateOf("Update from AiWay Native") }
    var repoOpen by remember { mutableStateOf(false) }
    var branchOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GitHub Native", style = MaterialTheme.typography.headlineSmall)
        Text("التطبيق يتصل مباشرة بـ GitHub API. استخدم Fine-grained Personal Access Token بصلاحية Contents للمستودعات المطلوبة.")
        OutlinedTextField(
            token, { token = it }, Modifier.fillMaxWidth(),
            label = { Text("GitHub token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveGithubToken(token); vm.connectGithub() }, enabled = !vm.busy) { Icon(Icons.Default.Login, null); Spacer(Modifier.width(6.dp)); Text("اتصال") }
            if (vm.githubUser.isNotBlank()) AssistChip(onClick = {}, label = { Text("@${vm.githubUser}") })
        }

        Box {
            OutlinedButton(onClick = { repoOpen = true }, enabled = vm.repos.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(vm.selectedRepo.ifBlank { "اختر المستودع" })
            }
            DropdownMenu(repoOpen, onDismissRequest = { repoOpen = false }) {
                vm.repos.forEach { repo ->
                    DropdownMenuItem(text = { Text(repo.fullName) }, onClick = { repoOpen = false; vm.selectRepo(repo) })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { branchOpen = true }, enabled = vm.branches.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(vm.selectedBranch.ifBlank { "اختر الفرع" })
            }
            DropdownMenu(branchOpen, onDismissRequest = { branchOpen = false }) {
                vm.branches.forEach { br ->
                    DropdownMenuItem(text = { Text(br.name) }, onClick = { branchOpen = false; vm.selectedBranch = br.name })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::loadRepo, enabled = !vm.busy && vm.selectedRepo.isNotBlank()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("تحميل") }
            OutlinedButton(onClick = vm::connectGithub, enabled = !vm.busy && token.isNotBlank()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("تحديث") }
        }
        HorizontalDivider()
        OutlinedTextField(commitMessage, { commitMessage = it }, Modifier.fillMaxWidth(), label = { Text("Commit message") })
        Button(onClick = { vm.pushRepo(commitMessage) }, enabled = !vm.busy && vm.selectedRepo.isNotBlank() && vm.files.isNotEmpty()) {
            Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("Push المشروع الحالي")
        }
        Text("ملفات المشروع المحلي: ${vm.files.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(vm: AiWayViewModel) {
    val context = LocalContext.current
    var modelOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ChatGPT / OpenAI مباشر", style = MaterialTheme.typography.headlineSmall)
        Text("هذه النسخة لا تستخدم Backend ولا WebView. تسجيل الدخول يتم في المتصفح الرسمي عبر OAuth + PKCE، ثم يعود إلى listener محلي على 127.0.0.1 داخل التطبيق. التوكنات تُخزن مشفرة بـ Android Keystore.")
        AssistChip(onClick = {}, label = { Text(vm.openAiStatus) })

        if (vm.openAiAccount.isBlank()) {
            Button(onClick = {
                vm.signInOpenAi { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }, enabled = !vm.busy) {
                Icon(Icons.Default.AccountCircle, null); Spacer(Modifier.width(6.dp)); Text("Continue with ChatGPT")
            }
        } else {
            Text("الحساب: ${vm.openAiAccount}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::refreshOpenAiModels, enabled = !vm.busy) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("تحديث الموديلات")
                }
                OutlinedButton(onClick = vm::logoutOpenAi, enabled = !vm.busy) {
                    Icon(Icons.Default.Logout, null); Spacer(Modifier.width(6.dp)); Text("تسجيل الخروج")
                }
            }
        }

        if (vm.openAiModels.isNotEmpty()) {
            Text("الموديل", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { modelOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    val chosen = vm.openAiModels.firstOrNull { it.slug == vm.selectedModel }
                    Text(chosen?.displayName ?: vm.selectedModel.ifBlank { "اختر موديل" })
                }
                DropdownMenu(modelOpen, onDismissRequest = { modelOpen = false }) {
                    vm.openAiModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Column { Text(model.displayName); Text(model.slug, style = MaterialTheme.typography.labelSmall) } },
                            onClick = { modelOpen = false; vm.chooseModel(model.slug) }
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Text("كيف يعمل", style = MaterialTheme.typography.titleMedium)
        Text("• OAuth ديناميكي بدون Client Secret\n• PKCE + state + nonce\n• callback محلي 127.0.0.1/auth/callback\n• تحقق RS256 من id_token عبر OpenAI JWKS\n• Refresh Token تلقائي قبل انتهاء الجلسة\n• استدعاء مباشر لـ /v1/models و /v1/responses\n• store=false و stream=true\n• أدوات ملفات محلية ينفذها التطبيق نفسه\n• Android Keystore لحماية بيانات الاعتماد")
        HorizontalDivider()
        Text("حول النسخة", style = MaterialTheme.typography.titleMedium)
        Text("• Kotlin + Jetpack Compose\n• لا تستخدم WebView\n• لا تحتاج Vercel أو Backend\n• GitHub API Native\n• OpenAI Responses API مباشر\n• الملفات محفوظة محلياً داخل التطبيق\n• متوافقة مع Codemagic")
    }
}
