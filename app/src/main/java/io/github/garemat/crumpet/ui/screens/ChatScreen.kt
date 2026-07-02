package io.github.garemat.crumpet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    val connected by vm.connected.collectAsStateWithLifecycle()
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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrumpetAvatar(Modifier.size(40.dp), active = thinking)
            Spacer(Modifier.width(11.dp))
            Column {
                Text("Crumpet", style = MaterialTheme.typography.titleLarge, color = Cream)
                Text(
                    when {
                        thinking -> "thinking…"
                        connected -> "connected"
                        chat.isNotEmpty() -> "offline — showing recent history"
                        else -> "offline"
                    },
                    color = if (connected) Jade else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(chat) { line -> Bubble(line) }
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
            IconButton(
                onClick = { send() },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Brass),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color(0xFF22170C)) }
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
private fun Bubble(line: ChatLine) {
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
                        contentDescription = "Sent image",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    if (line.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (line.text.isNotBlank()) {
                    Text(line.text, color = Cream, fontSize = 13.5.sp)
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
