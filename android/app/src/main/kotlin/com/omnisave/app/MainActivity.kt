package com.omnisave.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val Blue = Color(0xFF0A84FF)
private val Gray = Color(0xFF8E8E93)

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableStateOf(0)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val cfg = Sync.cfg(this)
        if (cfg.on) Sync.boot(this, cfg)
        setContent { OmnisaveTheme { Root() } }
    }

    override fun onResume() { super.onResume(); resumeTick++ } // re-evaluates permissions after user returns from app settings

    @Composable
    private fun Root() {
        val ctx = LocalContext.current
        key(resumeTick) {
            var micOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
            var notifOk by remember {
                mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            }
            val micReq = remember { registerForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it } }
            val notifReq = remember { registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it } }
            val overlayReq = remember { registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { } }

            if (!Settings.canDrawOverlays(ctx) || !micOk) {
                Onboarding(
                    overlayOk = Settings.canDrawOverlays(ctx),
                    onOverlay = {
                        overlayReq.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    },
                    micOk = micOk, onMic = { micReq.launch(Manifest.permission.RECORD_AUDIO) },
                    notifOk = notifOk, onNotif = { if (Build.VERSION.SDK_INT >= 33) notifReq.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
            } else Home()
        }
    }

    /* ---------- onboarding (PRD R7: plain-language explainers + direct jumps) ---------- */
    @Composable
    private fun Onboarding(overlayOk: Boolean, onOverlay: () -> Unit, micOk: Boolean, onMic: () -> Unit, notifOk: Boolean, onNotif: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Omnisave", style = MaterialTheme.typography.headlineMedium, color = Blue)
            Text("Save anything you scroll — a tiny bubble floats over every app. One tap, one reference line. Everything stays on your phone.", style = MaterialTheme.typography.bodyMedium)
            Step("1 · Float over other apps", "Android needs your explicit allow-list for the bubble.", overlayOk, onOverlay, "Allow display over apps")
            Step("2 · Microphone", "Only for ≤30s audio memos you start yourself. Text saving works without it.", micOk, onMic, "Allow microphone")
            Step("3 · Notifications", "Keeps the bubble service alive. No marketing, promise.", notifOk, onNotif, "Allow notifications")
            Text("Everything can be switched off later — the bubble respects that instantly.", style = MaterialTheme.typography.bodySmall, color = Gray)
        }
    }

    @Composable
    private fun Step(title: String, why: String, ok: Boolean, action: () -> Unit, label: String) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(why, style = MaterialTheme.typography.bodySmall, color = Gray)
                Button(onClick = action, enabled = !ok, colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text(if (ok) "✓ Done" else label) }
            }
        }
    }

    /* ---------- home: library / settings tabs ---------- */
    @Composable
    private fun Home() {
        var tab by rememberSaveable { mutableStateOf(0) }
        Scaffold(bottomBar = {
            NavigationBar(containerColor = Color(0xFFF5F5F7)) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Library") }, icon = { Icon(Icons.Filled.Bookmark, "Library") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Settings") }, icon = { Icon(Icons.Filled.Settings, "Settings") })
            }
        }) { pad -> Box(Modifier.padding(pad)) { if (tab == 0) Library() else SettingsView() } }
    }

    @Composable
    private fun Library() {
        val ctx = LocalContext.current
        val dao = AppDatabase.get(ctx).dao()
        val scope = rememberCoroutineScope()
        var q by rememberSaveable { mutableStateOf("") }
        var plat by rememberSaveable { mutableStateOf("") }
        var type by rememberSaveable { mutableStateOf("") }
        var memo by rememberSaveable { mutableStateOf(false) }
        val items by dao.byQuery(q).collectAsStateWithLifecycle(initialValue = emptyList(), lifecycleOwner = LocalLifecycleOwner.current)
        val filtered = items.filter { i ->
            (plat.isBlank() || i.platform == plat) && ((type.isBlank() && !memo) || i.type == type || (memo && i.audioPath != null))
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Omnisave", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text("${filtered.size}/${items.size}", style = MaterialTheme.typography.labelSmall, color = Gray)
                Spacer(Modifier.width(10.dp))
                BubbleToggle()
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filtered.isEmpty()) item { Text("Nothing saved here yet — copy a link anywhere and tap the bubble.", style = MaterialTheme.typography.bodyMedium, color = Gray) }
                items(filtered, key = { it.id }) { item ->
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.platform.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF0A6CD8))
                                Spacer(Modifier.width(6.dp))
                                Text(item.type + if (item.isShort) " · shorts" else "", style = MaterialTheme.typography.labelSmall, color = Color(0xFF48484A))
                            }
                            Text(item.title.ifBlank { item.url }, style = MaterialTheme.typography.titleSmall)
                            if (item.refText.isNotBlank()) Text(item.refText, style = MaterialTheme.typography.bodySmall)
                            item.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Gray) }
                            if (item.tagsList().isNotEmpty()) Text(item.tagsList().joinToString(" ") { "#" + it }, style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C4A92))
                            item.audioPath?.let { Text("memo ≤30s", style = MaterialTheme.typography.labelSmall, color = Color(0xFF248A3D)) }
                            Text("Saved " + android.text.format.DateUtils.getRelativeTimeSpanString(item.createdAt).toString(), style = MaterialTheme.typography.labelSmall, color = Gray)
                            TextButton(onClick = { scope.launch { dao.remove(item.id); Sync.pushRemove(item.id) } }) { Text("Delete", color = Color(0xFFFF3B30)) }
                        }
                    }
                }
            }
            // ---- bottom search + filters (PRD R5) ----
            Surface(tonalElevation = 6.dp, color = Color(0xFFEFF2F5)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(listOf("", "web", "youtube", "x", "linkedin", "instagram", "reddit", "discord", "notion")) { p ->
                            FChip(p.ifBlank { "All" }, plat == p) { plat = if (plat == p) "" else p }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(listOf("", "short", "video", "post", "article", "doc", "image", "website")) { t ->
                            FChip(t.ifBlank { "Types" }, type == t) { type = if (type == t) "" else t }
                        }
                        item { FChip("memo", memo) { memo = !memo } }
                    }
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search titles, references, URLs") }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue))
                }
            }
        }
    }

    @Composable
    private fun FChip(label: String, on: Boolean, click: () -> Unit) =
        FilterChip(selected = on, onClick = click, label = { Text(label) },
            colors = FilterChipDefaults.filterChipColors(containerColor = Color(0x1A0A84FF), selectedContainerColor = Blue))

    @Composable
    private fun BubbleToggle() {
        val ctx = LocalContext.current
        var on by remember { mutableStateOf(BubbleService.active) }
        Switch(on, { v ->
            on = v
            ContextCompat.startForegroundService(ctx, BubbleService.intent(ctx, !v)) // v=true→start(!v=false)
        })
    }

    /* ---------- settings ---------- */
    @Composable
    private fun SettingsView() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val p = ctx.getSharedPreferences("omnisave", Context.MODE_PRIVATE)
        var gemKey by rememberSaveable { mutableStateOf(p.getString("gemKey", "") ?: "") }
        var gemModel by rememberSaveable { mutableStateOf(p.getString("gemModel", "gemini-2.0-flash") ?: "gemini-2.0-flash") }
        var gemOn by rememberSaveable { mutableStateOf(p.getBoolean("gemOn", false)) }
        var syncOn by rememberSaveable { mutableStateOf(p.getBoolean("syncOn", false)) }
        var syncCode by rememberSaveable { mutableStateOf(p.getString("syncCode", "") ?: "") }
        var fbCfg by rememberSaveable { mutableStateOf(p.getString("fbConfig", "") ?: "") }
        var used by rememberSaveable { mutableStateOf("?") }
        LaunchedEffect(Unit) { used = "%.1f MB".format(Repo.storageUsed(ctx) / 1048576.0) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Capture") { Text("Bubble floats over every app; the link comes from your clipboard the moment you tap.", style = MaterialTheme.typography.bodySmall) }
            Section("Gemini (free model)") {
                OutlinedTextField(gemKey, { gemKey = it; GemClient.persist(ctx, gemOn, gemKey, gemModel) }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true)
                OutlinedTextField(gemModel, { gemModel = it; GemClient.persist(ctx, gemOn, gemKey, gemModel) }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(gemOn, { gemOn = it; GemClient.persist(ctx, it, gemKey, gemModel) })
                    Text("Auto-tag + one-line summary on save")
                }
            }
            Section("Sync (laptop ↔ phone)") {
                OutlinedTextField(fbCfg, { fbCfg = it }, Modifier.fillMaxWidth(), label = { Text("Firebase web config JSON") }, singleLine = false, minLines = 4)
                OutlinedTextField(syncCode, { syncCode = it }, Modifier.fillMaxWidth(), label = { Text("Sync code (same on both devices)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(syncOn, { v -> syncOn = v; Sync.persist(ctx, Sync.Cfg(v, syncCode, fbCfg)) })
                    Text("Sync on — AES-GCM encrypted with your code")
                }
                TextButton(onClick = {
                    Sync.persist(ctx, Sync.Cfg(syncOn, syncCode, fbCfg))
                    scope.launch {
                        val ok = Sync.boot(ctx, Sync.cfg(ctx))
                        if (ok) Sync.start(ctx, AppDatabase.get(ctx).dao())
                    }
                }) { Text("Save + connect", color = Blue) }
            }
            Section("Data") {
                Text("Storage used ~$used", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    scope.launch {
                        AppDatabase.get(ctx).dao().all().forEach { Sync.pushRemove(it.id) }
                        AppDatabase.get(ctx).dao().wipe(); used = "0 MB"
                    }
                }) { Text("Erase all saved items", color = Color(0xFFFF3B30)) }
            }
        }
    }

    @Composable
    private fun Section(h: String, block: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(h, style = MaterialTheme.typography.titleSmall, color = Blue)
                block()
            }
        }
    }
}

@Composable
fun OmnisaveTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue), content = content)
