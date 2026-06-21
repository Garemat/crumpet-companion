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
                    if (thinking) "thinking…" else if (connected) "connected" else "offline",
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

        val picker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) { vm.sendAttachment(uri, input); input = "" }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    picker.launch(arrayOf("image/*", "application/pdf", "text/*"))
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Bg3),
            ) { Icon(Icons.Filled.Add, "Attach a photo or file", tint = Jade) }
            Spacer(Modifier.width(8.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Message Crumpet…", color = Muted) },
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
                onClick = { vm.sendChat(input); input = "" },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Brass),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color(0xFF22170C)) }
        }
    }
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
            Text(line.text, color = Cream, fontSize = 13.5.sp)
        }
    }
}
