package com.aiway.nativeapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val vm: AiWayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AiWayApp(vm) }
    }

    override fun onStop() {
        // Persist chat + workspace synchronously so nothing is lost if Android kills the process.
        runCatching { vm.flush() }
        super.onStop()
    }
}

private fun relTime(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60000
    return when {
        m < 1 -> "الآن"
        m < 60 -> "منذ $m د"
        m < 1440 -> "منذ ${m / 60} س"
        else -> "منذ ${m / 1440} يوم"
    }
}

enum class AppPage(val label: String) {
    Chat("محادثة جديدة"), Files("مساحة العمل"), Preview("المعاينة"), Github("GitHub"), Settings("الإعدادات والموديل")
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
                        },
                        onOpen = { id ->
                            vm.openConversation(id)
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
                                    Box(
                                        Modifier.size(9.dp).clip(CircleShape)
                                            .background(if (vm.openAiAccount.isNotBlank()) Color(0xFF22C55E) else Color(0xFFF59E0B))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val title = if (page == AppPage.Chat) {
                                        vm.conversations.firstOrNull { it.id == vm.currentConvId }?.title ?: page.label
                                    } else page.label
                                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
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
                            AppPage.Preview -> PreviewScreen(vm) { fixPrompt -> page = AppPage.Chat; vm.sendPrompt(fixPrompt) }
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
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit
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
            DrawerItem(Icons.Default.PlayArrow, AppPage.Preview, current, onSelect)
            DrawerItem(Icons.Default.Settings, AppPage.Settings, current, onSelect)
            DrawerItem(Icons.Default.Cloud, AppPage.Github, current, onSelect)
            Spacer(Modifier.height(18.dp))
            Text("المحادثات المحفوظة", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            if (vm.conversations.isEmpty()) {
                Text("لا توجد محادثات بعد", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.conversations, key = { it.id }) { c ->
                        val active = c.id == vm.currentConvId
                        Surface(
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onOpen(c.id) }
                        ) {
                            Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Chat, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, maxLines = 1, fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                                    Text("${c.count} رسالة • ${relTime(c.updatedAt)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { vm.deleteConversation(c.id) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Delete, "حذف المحادثة", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
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
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length, vm.lastRun) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.size - 1 + (if (vm.lastRun != null) 1 else 0))
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (vm.messages.isEmpty()) {
                item { WelcomePanel(onSuggestion = { prompt = it }) }
            }
            items(vm.messages, key = { it.id }) { m ->
                MessageBubble(m, typing = vm.busy && m.id == vm.messages.lastOrNull()?.id)
            }
            vm.lastRun?.let { run -> item(key = "changes") { ChangesCard(run, vm) } }
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
                    placeholder = { Text(if (vm.agentMode == "plan") "صف ما تريد وسيضع AiWay خطة بدون تعديل الملفات…" else "اسأل AiWay أو صف ما تريد بناءه…") },
                    trailingIcon = {
                        FilledIconButton(
                            onClick = { val p = prompt; prompt = ""; vm.sendPrompt(p) },
                            enabled = prompt.isNotBlank() && !vm.busy
                        ) { Icon(Icons.Default.Send, "إرسال") }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = vm.agentMode == "plan",
                        onClick = { vm.updateAgentMode(if (vm.agentMode == "plan") "build" else "plan") },
                        label = { Text(if (vm.agentMode == "plan") "وضع: خطة" else "وضع: بناء", fontSize = 12.sp) }
                    )
                    Spacer(Modifier.width(8.dp))
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
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)))
                .padding(vertical = 26.dp, horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AiLogo()
                Spacer(Modifier.height(18.dp))
                Text("مرحباً، أنا AiWay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("من الفكرة إلى الكود. مساحة واحدة تفكر فيها، تبني، وتجرب ما تصنعه.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
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
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i"
            )
            Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = a)))
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, typing: Boolean = false) {
    val user = m.role == "user"
    val clipboard = LocalClipboardManager.current
    val primary = MaterialTheme.colorScheme.primary
    val bubbleShape = if (user) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.Start else Arrangement.End) {
        if (user) {
            Box(
                Modifier.fillMaxWidth(.88f).clip(bubbleShape)
                    .background(Brush.linearGradient(listOf(primary, Color(0xFF5B8DEF))))
                    .padding(14.dp)
            ) {
                SelectionContainer { Text(m.text, color = Color.White) }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(.97f),
                shape = bubbleShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(24.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text("A", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = primary) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("AiWay", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = primary)
                        Spacer(Modifier.weight(1f))
                        if (m.text.isNotBlank()) {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(m.text)) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.ContentCopy, "نسخ", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (m.text.isBlank() && typing) TypingDots() else SelectionContainer { Text(m.text.ifBlank { "…" }) }
                }
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
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportZip(it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importZip(it) } }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مساحة العمل", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            AssistChip(onClick = {}, label = { Text("${vm.files.size} ملف") })
            IconButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Icon(Icons.Default.Upload, "استيراد ZIP") }
            IconButton(onClick = { exportLauncher.launch("aiway-workspace.zip") }) { Icon(Icons.Default.Download, "تصدير ZIP") }
        }
        Row {
            AssistChip(onClick = vm::createAgentsFile, label = { Text("AGENTS.md — قواعد المشروع للوكيل", fontSize = 12.sp) }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) })
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
    var eventsOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

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

        SettingsCard("الاتصال والشبكة") {
            Text("يجرّب AiWay تلقائياً: DNS النظام ← DNS مشفّر (Cloudflare / Google / Quad9) ← آخر عناوين ناجحة ← إعادة المحاولة عند ضعف الشبكة.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = vm::runDiagnostics, enabled = !vm.checking, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                if (vm.checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("فحص الاتصال")
            }
            vm.diagnostics.forEach { c ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (c.ok) Icons.Default.CheckCircle else Icons.Default.Error, null,
                        tint = if (c.ok) Color(0xFF22C55E) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(c.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(c.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        SettingsCard("التخزين المحلي") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("كل محادثاتك وإعداداتك وتعديلات ملفاتك وتسجيل الدخول محفوظة على هذا الجهاز.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(vm.storageInfo.ifBlank { "—" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refreshStorageInfo(); vm.loadEvents(); eventsOpen = true }, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.History, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("سجل النشاط")
                }
                OutlinedButton(onClick = { confirmClear = true }, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("مسح المحادثات")
                }
            }
        }

        if (eventsOpen) EventsDialog(vm) { eventsOpen = false }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("مسح كل المحادثات؟") },
                text = { Text("سيتم حذف كل المحادثات المحفوظة على هذا الجهاز. لن تتأثر ملفات المشروع ولا تسجيل الدخول.") },
                confirmButton = { Button(onClick = { vm.clearAllConversations(); confirmClear = false }) { Text("مسح") } },
                dismissButton = { OutlinedButton(onClick = { confirmClear = false }) { Text("إلغاء") } }
            )
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
private fun ChangesCard(run: RunChanges, vm: AiWayViewModel) {
    var diffPath by remember { mutableStateOf<String?>(null) }
    val added = run.changes.sumOf { it.added }
    val removed = run.changes.sumOf { it.removed }
    val green = Color(0xFF22C55E)
    val red = Color(0xFFEF4444)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("مراجعة التعديلات • ${run.changes.size} ملف", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("+$added", color = green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text("−$removed", color = red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            run.changes.forEach { c ->
                val (label, color) = when (c.status) {
                    FileChange.Status.Added -> "جديد" to green
                    FileChange.Status.Deleted -> "محذوف" to red
                    FileChange.Status.Modified -> "معدّل" to MaterialTheme.colorScheme.primary
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { diffPath = c.path }.padding(vertical = 7.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = .15f)) {
                        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(c.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("+${c.added} −${c.removed}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::dismissRun, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("إبقاء")
                }
                OutlinedButton(onClick = vm::undoLastRun, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("تراجع عن الكل")
                }
            }
        }
    }
    diffPath?.let { path -> DiffDialog(run, path, vm) { diffPath = null } }
}

@Composable
private fun DiffDialog(run: RunChanges, path: String, vm: AiWayViewModel, onDismiss: () -> Unit) {
    val lines = remember(run, path) { DiffUtil.diff(run.before[path].orEmpty(), run.after[path].orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(path, fontSize = 14.sp, fontFamily = FontFamily.Monospace) },
        text = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                    items(lines.size) { i ->
                        val l = lines[i]
                        val bg = when (l.kind) {
                            '+' -> Color(0x3322C55E)
                            '-' -> Color(0x33EF4444)
                            else -> Color.Transparent
                        }
                        Text(
                            "${l.kind} ${l.text}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = { vm.revertFile(path); onDismiss() }) { Text("تراجع عن هذا الملف") } },
        dismissButton = { Button(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewScreen(vm: AiWayViewModel, onFix: (String) -> Unit) {
    var reload by remember { mutableStateOf(0) }
    val errors = remember { mutableStateListOf<String>() }
    val html = remember(reload) { vm.previewHtml() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("معاينة مباشرة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { errors.clear(); reload++ }) { Icon(Icons.Default.Refresh, "تحديث") }
        }
        if (html == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("أضف ملف index.html في مساحة العمل (أو اطلب من الوكيل بناءه) لتظهر المعاينة هنا.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = WebViewClient()
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) errors.add("${m.message()} (line ${m.lineNumber()})")
                                return true
                            }
                        }
                    }
                },
                update = { wv ->
                    if (wv.tag != reload) {
                        wv.tag = reload
                        wv.loadDataWithBaseURL("https://aiway.local/", html, "text/html", "utf-8", null)
                    }
                }
            )
        }
        if (errors.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("${errors.size} خطأ في الـ Console", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Button(
                        onClick = { onFix("المعاينة تُظهر أخطاء JavaScript التالية، افحص الملفات وأصلحها:\n" + errors.takeLast(8).joinToString("\n")) },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("أصلحها بالوكيل") }
                }
            }
        }
    }
}

private fun eventLabel(type: String) = when (type) {
    "login_start" -> "بدء تسجيل الدخول"
    "login_ok" -> "تسجيل دخول ناجح"
    "login_fail" -> "فشل تسجيل الدخول"
    "logout" -> "تسجيل خروج"
    "setting" -> "تغيير إعداد"
    "file_edit" -> "تعديل ملف"
    "file_add" -> "إضافة ملف"
    "file_delete" -> "حذف ملف"
    "conversation_deleted" -> "حذف محادثة"
    "conversations_cleared" -> "مسح المحادثات"
    "app_start" -> "فتح التطبيق"
    "github_connect" -> "اتصال GitHub"
    "github_token" -> "GitHub token"
    "repo_loaded" -> "تحميل مستودع"
    "repo_push" -> "Push إلى GitHub"
    "agent_run" -> "تشغيل الوكيل"
    "undo_run" -> "تراجع عن تعديلات الوكيل"
    "export_zip" -> "تصدير ZIP"
    "import_zip" -> "استيراد ZIP"
    else -> type
}

@Composable
private fun EventsDialog(vm: AiWayViewModel, onDismiss: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.US) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("سجل النشاط") },
        text = {
            if (vm.events.isEmpty()) Text("لا يوجد نشاط بعد", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                items(vm.events.size) { i ->
                    val e = vm.events[i]
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row {
                            Text(eventLabel(e.second), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(fmt.format(Date(e.first)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (e.third.isNotBlank()) Text(e.third, fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("تم") } }
    )
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
