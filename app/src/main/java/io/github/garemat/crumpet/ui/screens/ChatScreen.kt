package io.github.garemat.crumpet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.data.ChatLine
import io.github.garemat.crumpet.audio.VoiceState
import io.github.garemat.crumpet.ui.AppViewModel
import io.github.garemat.crumpet.ui.components.CrumpetAvatar
import io.github.garemat.crumpet.ui.theme.Bg2
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Line
import io.github.garemat.crumpet.ui.theme.Muted

@Composable
fun ChatScreen(vm: AppViewModel) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val voiceState by vm.voiceState.collectAsStateWithLifecycle()
    val voiceNote by vm.voiceNote.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var didInitialScroll by remember { mutableStateOf(false) }

    // Mark proactive pushes read while the chat is on screen (dismisses them on other devices).
    androidx.compose.runtime.DisposableEffect(Unit) {
        vm.setChatActive(true)
        onDispose { vm.setChatActive(false) }
    }

    LaunchedEffect(chat.size) {
        if (chat.isEmpty()) return@LaunchedEffect
        if (!didInitialScroll) {
            // First load (history just arrived): jump straight to the bottom — no slow
            // top-to-bottom animation through a long history.
            listState.scrollToItem(chat.size - 1)
            didInitialScroll = true
        } else {
            // New message during the session: a gentle animated scroll is nice here.
            listState.animateScrollToItem(chat.size - 1)
        }
    }

    val headerCtx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrumpetAvatar(
                Modifier.size(40.dp),
                active = thinking || activity != null || voiceState != VoiceState.Idle,
            )
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Crumpet", style = MaterialTheme.typography.titleLarge, color = Cream)
                Text(
                    when {
                        // Voice states first — a PTT turn is THIS device, right now.
                        voiceState == VoiceState.Recording -> "listening…"
                        voiceState == VoiceState.Thinking -> "thinking…"
                        voiceState == VoiceState.Speaking -> "speaking…"
                        voiceNote != null -> voiceNote!!
                        // A live milestone outranks the generic spinner — it's why the turn is long.
                        activity != null -> "⚙ $activity"
                        thinking -> "thinking…"
                        connected -> "connected"
                        chat.isNotEmpty() -> "offline — showing recent history"
                        else -> "offline"
                    },
                    color = when {
                        voiceState == VoiceState.Recording -> Brass
                        voiceState != VoiceState.Idle -> Jade
                        voiceNote != null -> Muted
                        activity != null -> Brass
                        connected -> Jade
                        else -> Muted
                    },
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.weight(1f))
            // Full-screen face (goes PiP when you switch to Maps etc.) — the car/desk mode.
            IconButton(
                onClick = {
                    headerCtx.startActivity(
                        android.content.Intent(headerCtx, io.github.garemat.crumpet.ui.FaceActivity::class.java),
                    )
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Bg3),
            ) { Icon(Icons.Filled.Fullscreen, "Full-screen Crumpet", tint = Jade) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(chat) { line -> Bubble(line, onFileTap = vm::saveToDownloads) }
        }

        // A file picked but not yet sent — staged so you can type a question first, then Send.
        var pending by remember { mutableStateOf<android.net.Uri?>(null) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val picker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) pending = uri }

        fun send() {
            val p = pending
            when {
                p != null -> { vm.sendAttachment(p, input); pending = null; input = "" }
                input.isNotBlank() -> { vm.sendChat(input); input = "" }
            }
        }

        // Staged-attachment chip (above the input row): shows the file, ✕ to remove.
        pending?.let { uri ->
            Row(
                Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Bg3)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, tint = Jade, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(fileLabel(ctx, uri), color = Cream, fontSize = 12.5.sp)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { pending = null }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, "Remove attachment", tint = Muted, modifier = Modifier.size(16.dp))
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { picker.launch(arrayOf("image/*", "application/pdf", "text/*")) },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Bg3),
            ) { Icon(Icons.Filled.Add, "Attach a photo or file", tint = Jade) }
            Spacer(Modifier.width(8.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(if (pending != null) "Ask about this…" else "Message Crumpet…", color = Muted)
                },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Bg3,
                    unfocusedContainerColor = Bg3,
                    focusedTextColor = Cream,
                    unfocusedTextColor = Cream,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Brass,
                ),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            // The action button is Send when there's something typed/staged, otherwise the PTT
            // mic (tap = record, tap again = send the utterance). While recording it stays a
            // Stop button even if text is typed — the recorder must never be left running.
            val recording = voiceState == VoiceState.Recording
            val micMode = recording || (input.isBlank() && pending == null)
            val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { granted -> if (granted) vm.toggleVoice() }
            IconButton(
                onClick = {
                    if (!micMode) send()
                    else if (androidx.core.content.ContextCompat.checkSelfPermission(
                            ctx, android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) vm.toggleVoice()
                    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (recording) Color(0xFFC0564C) else Brass),
            ) {
                when {
                    !micMode -> Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color(0xFF22170C))
                    recording -> Icon(Icons.Filled.Stop, "Stop and send", tint = Cream)
                    else -> Icon(Icons.Filled.Mic, "Talk to Crumpet", tint = Color(0xFF22170C))
                }
            }
        }
    }
}

private fun fileLabel(ctx: android.content.Context, uri: android.net.Uri): String {
    var name = "attachment"
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
        }
    }
    return if (name.length > 28) name.take(27) + "…" else name
}

@Composable
private fun Bubble(line: ChatLine, onFileTap: (ChatLine) -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (line.fromCrumpet) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (line.fromCrumpet) {
            CrumpetAvatar(Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .widthIn(max = 270.dp)
                .clip(
                    RoundedCornerShape(
                        18.dp, 18.dp,
                        if (line.fromCrumpet) 18.dp else 7.dp,
                        if (line.fromCrumpet) 7.dp else 18.dp,
                    ),
                )
                .background(if (line.fromCrumpet) Bg2 else Color(0x33E3A64C))
                .border(
                    1.dp,
                    if (line.fromCrumpet) Line else Color(0x4DE3A64C),
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Column {
                line.imageUri?.let { path ->
                    coil.compose.AsyncImage(
                        model = java.io.File(path),
                        contentDescription = if (line.fromCrumpet) "Image from Crumpet" else "Sent image",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            // Received images (charts, progress photos) save on tap.
                            .let { m ->
                                if (line.fileId != null) m.clickable { onFileTap(line) } else m
                            },
                    )
                    if (line.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                // A non-image file from Crumpet (patch, csv, …): a tappable save chip.
                if (line.fileId != null && line.imageUri == null) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Bg3)
                            .clickable { onFileTap(line) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📎", fontSize = 14.sp)
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Text(
                                line.fileName ?: "file",
                                color = Cream, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            )
                            Text("tap to save to Downloads", color = Muted, fontSize = 10.sp)
                        }
                    }
                    if (line.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (line.text.isNotBlank()) {
                    Text(line.text, color = Cream, fontSize = 13.5.sp)
                }
                // Light source label on the user's lines from OTHER channels ("via discord",
                // "via voice:desk") — the one thread shows where each turn happened.
                if (!line.fromCrumpet && line.source != null && line.source != "app") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "via ${line.source}",
                        color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                if (line.pending) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "queued — sends when connected",
                        color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
