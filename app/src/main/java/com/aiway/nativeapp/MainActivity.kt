package com.aiway.nativeapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AiWayApp() }
    }
}

enum class AppPage(val label: String) {
    Chat("محادثة جديدة"), Files("مساحة العمل"), Github("GitHub"), Settings("الإعدادات والموديل")
}

private val AiBlue = Color(0xFF2F6FED)
private val AiBlueSoft = Color(0xFFEFF4FF)
private val LightBg = Color(0xFFFBFCFF)
private val DarkBg = Color(0xFF0F1420)

private fun aiWayLight() = lightColorScheme(
    primary = AiBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FF),
    onPrimaryContainer = Color(0xFF173B7A),
    background = LightBg,
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F5FA),
    outline = Color(0xFFD8DEE9),
    onBackground = Color(0xFF172033),
    onSurface = Color(0xFF172033),
    onSurfaceVariant = Color(0xFF667085)
)

private fun aiWayDark() = darkColorScheme(
    primary = Color(0xFF6EA0FF),
    onPrimary = Color(0xFF071A3A),
    primaryContainer = Color(0xFF173B7A),
    onPrimaryContainer = Color(0xFFDCE8FF),
    background = DarkBg,
    surface = Color(0xFF171D2A),
    surfaceVariant = Color(0xFF202838),
    outline = Color(0xFF39445A),
    onBackground = Color(0xFFF4F7FF),
    onSurface = Color(0xFFF4F7FF),
    onSurfaceVariant = Color(0xFFAEB8CA)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiWayApp(vm: AiWayViewModel = viewModel()) {
    var page by remember { mutableStateOf(AppPage.Chat) }
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm.notice) {
        val n = vm.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(n)
        vm.notice = null
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (vm.darkMode) aiWayDark() else aiWayLight()) {
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    AiWayDrawer(
                        vm = vm,
                        current = page,
                        onSelect = {
                            page = it
                            scope.launch { drawer.close() }
                        },
                        onNewChat = {
                            vm.newChat()
                            page = AppPage.Chat
                            scope.launch { drawer.close() }
                        }
                    )
                }
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        TopAppBar(
                            modifier = Modifier.statusBarsPadding(),
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawer.open() } }) {
                                    Icon(Icons.Default.Menu, "القائمة")
                                }
                            },
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(page.label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                            },
                            actions = {
                                if (page == AppPage.Chat && vm.openAiModels.isNotEmpty()) {
                                    CompactModelPicker(vm)
                                }
                                IconButton(onClick = { vm.updateDarkMode(!vm.darkMode) }) {
                                    Icon(if (vm.darkMode) Icons.Default.LightMode else Icons.Default.DarkMode, "تبديل الوضع")
                                }
                                if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                        )
                    }
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        when (page) {
                            AppPage.Chat -> ChatScreen(vm)
                            AppPage.Files -> FilesScreen(vm)
                            AppPage.Github -> GithubScreen(vm)
                            AppPage.Settings -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiWayDrawer(
    vm: AiWayViewModel,
    current: AppPage,
    onSelect: (AppPage) -> Unit,
    onNewChat: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight().width(330.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiLogo(52.dp)
                Spacer(Modifier.width(12.dp))
                Text("AiWay", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(22.dp))
            Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("محادثة جديدة", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            DrawerItem(Icons.Default.Folder, AppPage.Files, current, onSelect)
            DrawerItem(Icons.Default.Settings, AppPage.Settings, current, onSelect)
            DrawerItem(Icons.Default.Cloud, AppPage.Github, current, onSelect)
            Spacer(Modifier.height(18.dp))
            Text("المحادثات الأخيرة", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            val recent = vm.messages.filter { it.role == "user" }.takeLast(4).reversed()
            if (recent.isEmpty()) {
                Text("لا توجد محادثات بعد", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            } else {
                recent.forEach { msg ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(msg.text, maxLines = 1, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("A", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("مساحة عملك", fontWeight = FontWeight.Bold)
                    Text("محفوظة على هذا الجهاز", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, page: AppPage, current: AppPage, onSelect: (AppPage) -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null) },
        label = { Text(page.label) },
        selected = current == page,
        onClick = { onSelect(page) },
        modifier = Modifier.padding(vertical = 2.dp),
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun AiLogo(size: androidx.compose.ui.unit.Dp = 86.dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(size / 3f),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 8.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("A", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * .46f).sp)
        }
    }
}

@Composable
private fun CompactModelPicker(vm: AiWayViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(14.dp)) {
            Text(vm.openAiModels.firstOrNull { it.slug == vm.selectedModel }?.displayName ?: "الموديل", maxLines = 1, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vm.openAiModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = { vm.chooseModel(model.slug); open = false }
                )
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: AiWayViewModel) {
    var prompt by remember { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (vm.messages.isEmpty()) {
                item { WelcomePanel(onSuggestion = { prompt = it }) }
            }
            items(vm.messages, key = { it.id }) { m ->
                MessageBubble(m)
            }
        }

        if (vm.agentActivity.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(vm.agentActivity, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp).navigationBarsPadding()) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    placeholder = { Text("اسأل AiWay أو صف ما تريد بناءه…") },
                    trailingIcon = {
                        FilledIconButton(
                            onClick = { val p = prompt; prompt = ""; vm.sendPrompt(p) },
                            enabled = prompt.isNotBlank() && !vm.busy
                        ) { Icon(Icons.Default.Send, "إرسال") }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { toolsOpen = true }, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Build, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("الأدوات", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (vm.selectedModel.isNotBlank()) Text(vm.selectedModel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("قد يخطئ AiWay، راجع الكود قبل الاستخدام في الإنتاج.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (toolsOpen) {
        ToolDialog(vm = vm, onDismiss = { toolsOpen = false })
    }
}

@Composable
private fun WelcomePanel(onSuggestion: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        AiLogo()
        Spacer(Modifier.height(22.dp))
        Text("مرحباً، أنا AiWay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text("من الفكرة إلى الكود. مساحة واحدة تفكر فيها، تبني، وتجرب ما تصنعه.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(26.dp))
        SuggestionCard(Icons.Default.Edit, "ابنِ واجهة جديدة", "حوّل فكرتك إلى تجربة حقيقية", "ابنِ لي واجهة Android حديثة ومتجاوبة") { onSuggestion(it) }
        SuggestionCard(Icons.Default.Search, "راجع الكود وحسّنه", "اكتشف الأخطاء والفرص المخفية", "راجع ملفات المشروع وحسّن الجودة والأداء") { onSuggestion(it) }
        SuggestionCard(Icons.Default.Build, "أصلح مشكلة برمجية", "حلول واضحة بلا تعقيد", "افحص المشروع وابحث عن سبب المشكلة وأصلحها") { onSuggestion(it) }
        SuggestionCard(Icons.Default.Code, "تعلّم شيئاً جديداً", "من المفهوم إلى التطبيق", "اشرح لي هذا المشروع ثم اقترح تحسيناً عملياً") { onSuggestion(it) }
    }
}

@Composable
private fun SuggestionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, prompt: String, onClick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onClick(prompt) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val user = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.Start else Arrangement.End) {
        Card(
            modifier = Modifier.fillMaxWidth(if (user) .90f else .96f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(if (user) "أنت" else "AiWay", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(5.dp))
                SelectionContainer { Text(m.text.ifBlank { "…" }) }
            }
        }
    }
}

@Composable
private fun ToolDialog(vm: AiWayViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("أدوات AiWay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolSwitch("قراءة ملفات المشروع", "list_files + read_file", vm.toolRead, vm::updateToolRead)
                ToolSwitch("إنشاء وتعديل الملفات", "write_file", vm.toolWrite, vm::updateToolWrite)
                ToolSwitch("حذف الملفات", "مغلق افتراضياً للأمان", vm.toolDelete, vm::updateToolDelete)
                ToolSwitch("البحث في الويب", "يخضع لدعم الموديل وسياسة الحساب", vm.toolWebSearch, vm::updateToolWebSearch)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("تم") } }
    )
}

@Composable
private fun ToolSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun FilesScreen(vm: AiWayViewModel) {
    var newName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مساحة العمل", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            AssistChip(onClick = {}, label = { Text("${vm.files.size} ملف") })
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(132.dp).fillMaxHeight()) {
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.files.keys.toList()) { name ->
                        val selected = vm.selectedFile == name
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { vm.selectedFile = name }
                        ) { Text(name, Modifier.padding(9.dp), fontSize = 11.sp, maxLines = 2) }
                    }
                }
                OutlinedTextField(newName, { newName = it }, label = { Text("ملف جديد") }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                Row {
                    IconButton(onClick = { vm.addFile(newName); newName = "" }) { Icon(Icons.Default.Add, "إضافة") }
                    IconButton(onClick = vm::deleteSelectedFile, enabled = vm.selectedFile != null) { Icon(Icons.Default.Delete, "حذف") }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(vm.selectedFile ?: "لا يوجد ملف", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = vm.selectedFile?.let { vm.files[it] }.orEmpty(),
                        onValueChange = vm::updateFile,
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(14.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        enabled = vm.selectedFile != null
                    )
                }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GitHub", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("اتصال Native مباشر بـ GitHub API. استخدم Fine-grained token بصلاحية Contents للمستودعات المطلوبة.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("GitHub token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, shape = RoundedCornerShape(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveGithubToken(token); vm.connectGithub() }, enabled = !vm.busy) { Icon(Icons.Default.Login, null); Spacer(Modifier.width(6.dp)); Text("اتصال") }
            if (vm.githubUser.isNotBlank()) AssistChip(onClick = {}, label = { Text("@${vm.githubUser}") })
        }
        Box {
            OutlinedButton(onClick = { repoOpen = true }, enabled = vm.repos.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(vm.selectedRepo.ifBlank { "اختر المستودع" }) }
            DropdownMenu(repoOpen, onDismissRequest = { repoOpen = false }) {
                vm.repos.forEach { repo -> DropdownMenuItem(text = { Text(repo.fullName) }, onClick = { repoOpen = false; vm.selectRepo(repo) }) }
            }
        }
        Box {
            OutlinedButton(onClick = { branchOpen = true }, enabled = vm.branches.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(vm.selectedBranch.ifBlank { "اختر الفرع" }) }
            DropdownMenu(branchOpen, onDismissRequest = { branchOpen = false }) {
                vm.branches.forEach { br -> DropdownMenuItem(text = { Text(br.name) }, onClick = { branchOpen = false; vm.selectedBranch = br.name }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::loadRepo, enabled = !vm.busy && vm.selectedRepo.isNotBlank()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("تحميل") }
            OutlinedButton(onClick = vm::connectGithub, enabled = !vm.busy && token.isNotBlank()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("تحديث") }
        }
        HorizontalDivider()
        OutlinedTextField(commitMessage, { commitMessage = it }, Modifier.fillMaxWidth(), label = { Text("Commit message") }, shape = RoundedCornerShape(16.dp))
        Button(onClick = { vm.pushRepo(commitMessage) }, enabled = !vm.busy && vm.selectedRepo.isNotBlank() && vm.files.isNotEmpty()) {
            Icon(Icons.Default.Upload, null); Spacer(Modifier.width(6.dp)); Text("Push المشروع الحالي")
        }
    }
}

@Composable
private fun SettingsScreen(vm: AiWayViewModel) {
    val context = LocalContext.current
    var modelOpen by remember { mutableStateOf(false) }

    fun openExternal(url: String) {
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
        }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("الإعدادات والموديل", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)

        SettingsCard("المظهر") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (vm.darkMode) Icons.Default.DarkMode else Icons.Default.LightMode, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (vm.darkMode) "الوضع الليلي" else "الوضع النهاري", fontWeight = FontWeight.Bold)
                    Text("تبديل فوري لكل واجهات AiWay", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.darkMode, onCheckedChange = vm::updateDarkMode)
            }
        }

        SettingsCard("ChatGPT") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.openAiAccount.ifBlank { "غير مسجل" }, fontWeight = FontWeight.Bold)
                    Text(vm.openAiStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (vm.openAiAccount.isBlank()) {
                Button(
                    onClick = {
                        vm.signInOpenAi { url ->
                            runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
                                .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.AccountCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Continue with ChatGPT")
                }
                Text("بعد الموافقة، سينتقل المتصفح إلى 127.0.0.1 داخل الهاتف. عندما تظهر رسالة نجاح، ارجع إلى AiWay وسيكمل تسجيل الدخول تلقائياً.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::refreshOpenAiModels, enabled = !vm.busy) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(5.dp)); Text("تحديث") }
                    OutlinedButton(onClick = vm::logoutOpenAi, enabled = !vm.busy) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(5.dp)); Text("خروج") }
                }
            }
        }

        SettingsCard("اختيار الموديل") {
            if (vm.openAiModels.isEmpty()) {
                Text("سجّل الدخول أولاً ليتم جلب الموديلات المتاحة لحسابك من OpenAI.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Box {
                    OutlinedButton(onClick = { modelOpen = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text(vm.openAiModels.firstOrNull { it.slug == vm.selectedModel }?.displayName ?: vm.selectedModel, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = modelOpen, onDismissRequest = { modelOpen = false }) {
                        vm.openAiModels.forEach { m -> DropdownMenuItem(text = { Column { Text(m.displayName); Text(m.slug, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { vm.chooseModel(m.slug); modelOpen = false }) }
                    }
                }
            }
        }

        SettingsCard("الاستخدام والحصة") {
            Text("حصة ChatGPT", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageMini("٥ ساعات", "عرض في ChatGPT", Modifier.weight(1f))
                UsageMini("أسبوعي", "عرض في ChatGPT", Modifier.weight(1f))
                UsageMini("Reset", "إن كان متاحاً", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text("OpenAI لا توفر حالياً API موثقاً للتطبيقات المفتوحة المصدر لقراءة النسبة الدقيقة أو موعد إعادة التعيين. لذلك يعرض AiWay الصفحة الرسمية بدل تخمين أرقام غير صحيحة.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { openExternal("https://chatgpt.com/settings/usage") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("فتح ChatGPT Settings → Usage")
            }
        }

        SettingsCard("أدوات الوكيل") {
            ToolSwitch("قراءة ملفات المشروع", "قراءة قائمة الملفات ومحتواها", vm.toolRead, vm::updateToolRead)
            ToolSwitch("إنشاء وتعديل الملفات", "السماح للوكيل بتطبيق التعديلات", vm.toolWrite, vm::updateToolWrite)
            ToolSwitch("حذف الملفات", "مغلق افتراضياً للأمان", vm.toolDelete, vm::updateToolDelete)
            ToolSwitch("البحث في الويب", "Hosted web_search عندما يدعمه الموديل والحساب", vm.toolWebSearch, vm::updateToolWebSearch)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun UsageMini(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
