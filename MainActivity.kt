package com.potato.ffpatcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF121212)
private val Card = Color(0xFF1E1E1E)
private val Card2 = Color(0xFF2B2B2B)
private val Line = Color(0xFF383838)
private val TextMain = Color(0xFFE8E8E8)
private val TextMuted = Color(0xFF9A9A9A)
private val Accent = Color(0xFFD6D6D6)
private val Good = Color(0xFF7BC47F)
private val Bad = Color(0xFFCF6679)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color(0xFF111111),
                    background = Bg,
                    onBackground = TextMain,
                    surface = Card,
                    onSurface = TextMain,
                    surfaceVariant = Card2,
                    onSurfaceVariant = TextMuted,
                    outline = Line,
                    error = Bad
                )
            ) {
                PatcherScreen()
            }
        }
    }
}

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "patch.zip"

@Composable
private fun PatcherScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("patcher", Context.MODE_PRIVATE) }

    var gameIdx by remember { mutableIntStateOf(0) }
    val game = Game.values()[gameIdx]

    val treeUris = remember {
        mutableStateMapOf<String, String>().apply {
            Game.values().forEach { g ->
                prefs.getString("tree_${g.pkg}", null)?.let { put(g.pkg, it) }
            }
        }
    }
    var target by remember { mutableStateOf(prefs.getString("target", PatchEngine.DEFAULT_TARGET)!!) }
    var patchUri by remember { mutableStateOf<Uri?>(null) }
    var patchName by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val logs = remember { mutableStateListOf("Siap. Pilih folder data game dulu.") }

    fun log(msg: String) {
        logs.add(0, msg)
        while (logs.size > 40) logs.removeAt(logs.size - 1)
    }

    val folderUri = treeUris[game.pkg]?.let { Uri.parse(it) }
    val patchedCount = remember(tick, gameIdx) { PatchEngine.patchedCount(ctx, game.pkg) }
    val gameInstalled = remember(gameIdx) {
        ctx.packageManager.getLaunchIntentForPackage(game.pkg) != null
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val base = PatchEngine.resolveBase(ctx, uri, game.pkg)
            if (base == null) {
                log("Folder salah. Pilih folder ${game.pkg}.")
            } else {
                treeUris[game.pkg] = uri.toString()
                prefs.edit().putString("tree_${game.pkg}", uri.toString()).apply()
                log("Folder ${game.label} terhubung.")
            }
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            patchUri = uri
            patchName = displayName(ctx, uri)
            log("Patch dipilih: $patchName")
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text("Potato Patcher", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Terapkan patch tekstur low-poly ke data game",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }

            // Pilihan game
            Surface(
                color = Card,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Line),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Game.values().forEachIndexed { i, g ->
                        val sel = i == gameIdx
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Card2 else Color.Transparent)
                                .clickable(enabled = !busy) { gameIdx = i }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                g.label,
                                color = if (sel) TextMain else TextMuted,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // Status
            Surface(
                color = Card,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Line),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusRow("Game terpasang", gameInstalled, if (gameInstalled) "Ya" else "Tidak ditemukan")
                    StatusRow("Folder data", folderUri != null, if (folderUri != null) "Terhubung" else "Belum dipilih")
                    StatusRow(
                        "Patch aktif",
                        patchedCount > 0,
                        if (patchedCount > 0) "$patchedCount file" else "Tidak ada"
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= 33) {
                Surface(
                    color = Card,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Line),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Android 13+ memblokir akses ke Android/data lewat pemilih folder, " +
                            "jadi langkah 1 kemungkinan besar ditolak sistem. Cara ini paling andal di Android 11–12.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            StepCard(
                1, "Folder data game",
                "Tekan “Gunakan folder ini” di pemilih folder",
                if (folderUri == null) "Pilih folder" else "Ganti folder",
                enabled = !busy
            ) {
                val initial = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Android/data/${game.pkg}"
                )
                treeLauncher.launch(initial)
            }

            Surface(
                color = Card,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Line),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Folder tujuan di dalam data game", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = target,
                        onValueChange = {
                            target = it
                            prefs.edit().putString("target", it).apply()
                        },
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            target = PatchEngine.DEFAULT_TARGET
                            prefs.edit().putString("target", target).apply()
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Reset ke default") }
                }
            }

            StepCard(
                2, "File patch (.zip)",
                patchName ?: "Isi zip relatif terhadap folder tujuan",
                if (patchUri == null) "Pilih patch" else "Ganti patch",
                enabled = !busy
            ) {
                zipLauncher.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                )
            }

            StepCard(
                3, "Terapkan",
                "File asli di-backup otomatis sebelum ditimpa",
                "Terapkan patch",
                enabled = !busy && folderUri != null && patchUri != null,
                primary = true
            ) {
                val tree = folderUri!!
                val zip = patchUri!!
                scope.launch {
                    busy = true
                    log("Menerapkan patch ke ${game.label}…")
                    try {
                        val r = withContext(Dispatchers.IO) {
                            PatchEngine.apply(ctx, game.pkg, tree, target, zip) { m -> log(m) }
                        }
                        log("Selesai: ${r.applied} file diterapkan, ${r.backedUp} di-backup, ${r.skipped} dilewati.")
                    } catch (e: Exception) {
                        log("Gagal: ${e.message}")
                    } finally {
                        busy = false
                        tick++
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    val tree = folderUri ?: return@OutlinedButton
                    scope.launch {
                        busy = true
                        log("Memulihkan file asli…")
                        try {
                            val n = withContext(Dispatchers.IO) {
                                PatchEngine.restore(ctx, game.pkg, tree) { m -> log(m) }
                            }
                            log("Pulih: $n file dikembalikan.")
                        } catch (e: Exception) {
                            log("Gagal memulihkan: ${e.message}")
                        } finally {
                            busy = false
                            tick++
                        }
                    }
                },
                enabled = !busy && folderUri != null && patchedCount > 0,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) { Text("Pulihkan ke original") }

            OutlinedButton(
                onClick = {
                    ctx.packageManager.getLaunchIntentForPackage(game.pkg)?.let { ctx.startActivity(it) }
                },
                enabled = !busy && gameInstalled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) { Text("Buka ${game.label}") }

            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = Card2
                )
            }

            // Log
            Surface(
                color = Color(0xFF181818),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Line),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Log", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    logs.take(8).forEach {
                        Text("› $it", color = TextMain, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Text(
                "Memodifikasi file game melanggar ketentuan Garena dan bisa membuat akun diblokir. " +
                    "Gunakan dengan risiko sendiri, sebaiknya di akun cadangan.",
                color = TextMuted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (ok) Good else Bad)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StepCard(
    n: Int,
    title: String,
    desc: String,
    button: String,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = Card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Card2),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$n", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(desc, color = TextMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (primary) {
                Button(
                    onClick = onClick,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color(0xFF111111),
                        disabledContainerColor = Card2,
                        disabledContentColor = TextMuted
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) { Text(button, fontWeight = FontWeight.SemiBold) }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) { Text(button) }
            }
        }
    }
}
