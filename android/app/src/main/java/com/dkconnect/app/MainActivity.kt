package com.dkconnect.app

import android.content.Context
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.widget.VideoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.Coil
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.compose.AsyncImage
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import com.dkconnect.app.data.RemoteApi
import com.dkconnect.app.data.RealtimeClient
import com.dkconnect.app.data.DkFriend
import com.dkconnect.app.data.DkMessage
import com.dkconnect.app.data.DkStory

private val Ink = Color(0xFF080A12)
private val Panel = Color(0xFF151A29)
private val Muted = Color(0xFF9299AA)
private val Violet = Color(0xFF9B7BFF)
private val Cyan = Color(0xFF69D9D0)
private val Pink = Color(0xFFFF78B7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            Coil.setImageLoader(ImageLoader.Builder(this).components { add(ImageDecoderDecoder.Factory()) }.build())
        }
        setContent { DKConnectApp() }
    }
}

private fun prefs(context: Context) = context.getSharedPreferences("dkconnect_ui", Context.MODE_PRIVATE)
private fun loadMessages(context: Context): List<String> {
    val raw = prefs(context).getString("chat_messages", null) ?: return listOf("Chào bạn! DK Connect đã sẵn sàng 💜", "Giao diện chat mới đang được hoàn thiện.")
    return try { val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) } } catch (_: Exception) { emptyList() }
}
private fun saveMessages(context: Context, messages: List<String>) {
    val a = JSONArray(); messages.takeLast(300).forEach { a.put(it) }; prefs(context).edit().putString("chat_messages", a.toString()).apply()
}

@Composable
fun DKConnectApp() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf("Khoảnh khắc") }
    var showAuth by remember { mutableStateOf(true) }
    var authToken by remember { mutableStateOf(prefs(context).getString("auth_token", "") ?: "") }
    var apiBaseUrl by remember { mutableStateOf(prefs(context).getString("api_base_url", com.dkconnect.app.data.ApiConfig.BASE_URL) ?: com.dkconnect.app.data.ApiConfig.BASE_URL) }
    var activePeer by remember { mutableStateOf<DkFriend?>(null) }
    val realtime = remember(authToken, apiBaseUrl) { RealtimeClient(apiBaseUrl, authToken) }
    DisposableEffect(realtime, authToken) { if (authToken.isNotBlank()) realtime.connect(); onDispose { realtime.disconnect() } }
    var sessionChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val token = prefs(context).getString("auth_token", null)
        if (!token.isNullOrBlank()) {
            val valid = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(apiBaseUrl.trimEnd('/') + "/api/me").header("Authorization", "Bearer $token").get().build()
                    OkHttpClient().newCall(request).execute().use { it.isSuccessful }
                } catch (_: Exception) { false }
            }
            showAuth = !valid
            if (!valid) { authToken = ""; prefs(context).edit().remove("auth_token").apply() } else authToken = token
        }
        sessionChecked = true
    }
    var showSettings by remember { mutableStateOf(false) }
    var themeChoice by remember { mutableStateOf(prefs(context).getString("theme", "Tím cực quang") ?: "Tím cực quang") }
    var backgroundChoice by remember { mutableStateOf(prefs(context).getString("background", "Đêm vũ trụ") ?: "Đêm vũ trụ") }
    var performanceMode by remember { mutableStateOf(prefs(context).getString("performance", "Tự động") ?: "Tự động") }
    val motionEnabled = performanceMode != "Tiết kiệm pin"
    val accent = when (themeChoice) { "Hồng neon" -> Pink; "Xanh băng" -> Cyan; "Vàng champagne" -> Color(0xFFE8C77A); else -> Violet }
    val bg = when (backgroundChoice) { "Đen tối giản" -> Color(0xFF050608); "Biển đêm" -> Color(0xFF071B27); "Tím mộng" -> Color(0xFF170F28); else -> Ink }
    MaterialTheme(colorScheme = darkColorScheme(background = bg, surface = Panel, primary = accent, secondary = Cyan, onBackground = Color.White, onSurface = Color.White)) {
        if (!sessionChecked) {
            Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
        } else if (showAuth) AuthScreen(apiBaseUrl, onBaseUrlChange = { apiBaseUrl = it; prefs(context).edit().putString("api_base_url", it).apply() }, onAuthenticated = { token -> authToken = token; prefs(context).edit().putString("auth_token", token).apply(); showAuth = false }) else {
            Scaffold(containerColor = bg, bottomBar = {
                NavigationBar(containerColor = Panel.copy(alpha = .97f), contentColor = Color.White, tonalElevation = 0.dp) {
                    listOf("Khoảnh khắc" to Icons.Default.AutoAwesome, "Tin nhắn" to Icons.Default.Forum, "Bạn bè" to Icons.Default.PeopleAlt, "Cuộc gọi" to Icons.Default.Call, "Cá nhân" to Icons.Default.Person).forEach { (name, icon) ->
                        NavigationBarItem(selected = selected == name, onClick = { selected = name }, icon = {
                            androidx.compose.animation.AnimatedContent(selected == name, label = "tab-icon") { active ->
                                Icon(icon, null, tint = if (active) accent else Muted, modifier = Modifier.size(if (active) 25.dp else 22.dp))
                            }
                        }, label = { Text(name, fontSize = 9.sp, maxLines = 1) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = accent, selectedTextColor = accent, indicatorColor = accent.copy(.15f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).background(Brush.verticalGradient(listOf(bg, bg.copy(red = (bg.red + .035f).coerceAtMost(1f), blue = (bg.blue + .06f).coerceAtMost(1f)))))) {
                    AnimatedContent(targetState = selected, label = "main-tab", transitionSpec = { if (motionEnabled) (fadeIn() + slideInHorizontally { it / 9 } + scaleIn(initialScale = .985f)) togetherWith (fadeOut() + slideOutHorizontally { -it / 12 }) else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None }) { page ->
                        when (page) {
                            "Khoảnh khắc" -> MomentsScreen(accent, motionEnabled, authToken, apiBaseUrl)
                            "Tin nhắn" -> ChatScreen(accent, motionEnabled, authToken, apiBaseUrl, activePeer, realtime, onPeer = { activePeer = it })
                            "Bạn bè" -> FriendsScreen(accent, authToken, apiBaseUrl, realtime, onChat = { activePeer = it; selected = "Tin nhắn" })
                            "Cuộc gọi" -> CallsScreen(accent)
                            else -> ProfileScreen(accent, onSettings = { showSettings = true })
                        }
                    }
                }
            }
            if (showSettings) SettingsDialog(themeChoice, backgroundChoice, performanceMode, apiBaseUrl, onApiUrl = { apiBaseUrl = it.trimEnd('/') + "/"; prefs(context).edit().putString("api_base_url", apiBaseUrl).apply() }, onTheme = { themeChoice = it; prefs(context).edit().putString("theme", it).apply() }, onBackground = { backgroundChoice = it; prefs(context).edit().putString("background", it).apply() }, onPerformance = { performanceMode = it; prefs(context).edit().putString("performance", it).apply() }, onDismiss = { showSettings = false })
        }
    }
}

@Composable
fun AuthScreen(baseUrl: String, onBaseUrlChange: (String) -> Unit, onAuthenticated: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val client = remember { OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(15)).build() }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF21183B), Ink, Ink))).padding(26.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(70.dp).background(Brush.linearGradient(listOf(Violet, Cyan, Pink)), RoundedCornerShape(24.dp)), Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(24.dp)); Text("DK CONNECT", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("Kết nối cảm xúc · Chia sẻ từng khoảnh khắc", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp)); OutlinedTextField(value = baseUrl, onValueChange = onBaseUrlChange, label = { Text("Địa chỉ máy chủ API") }, placeholder = { Text("https://api-cua-ban.onrender.com/") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp)); Text(if (register) "Tạo tài khoản" else "Chào mừng trở lại", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            if (register) { Spacer(Modifier.height(12.dp)); OutlinedTextField(displayName, { displayName = it }, label = { Text("Tên hiển thị") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            Spacer(Modifier.height(18.dp)); OutlinedTextField(email, { email = it; error = null }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp)); OutlinedTextField(password, { password = it; error = null }, label = { Text("Mật khẩu (ít nhất 8 ký tự)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = Color(0xFFFF8B9A), fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp)) }
            Spacer(Modifier.height(20.dp)); Button(enabled = !busy, onClick = {
                if (email.isBlank() || password.length < 8 || (register && displayName.trim().length < 2)) { error = "Vui lòng nhập thông tin hợp lệ. Mật khẩu tối thiểu 8 ký tự."; return@Button }
                busy = true; error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val payload = JSONObject().put("email", email.trim()).put("password", password)
                            if (register) payload.put("displayName", displayName.trim())
                            val path = if (register) "api/auth/register" else "api/auth/login"
                            val request = Request.Builder().url(baseUrl.trimEnd('/') + "/" + path)
                                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                            client.newCall(request).execute().use { response ->
                                val body = response.body?.string().orEmpty()
                                val json = JSONObject(body.ifBlank { "{}" })
                                if (!response.isSuccessful) Result.failure<String>(IllegalStateException(json.optString("message", json.optString("error", "Không thể đăng nhập."))))
                                else Result.success(json.optString("token").takeIf { it.isNotBlank() } ?: throw IllegalStateException("Máy chủ không trả token."))
                            }
                        } catch (e: Exception) { Result.failure<String>(e) }
                    }
                    busy = false
                    result.onSuccess(onAuthenticated).onFailure { error = when (it) { is java.net.UnknownHostException, is java.net.ConnectException -> "Không kết nối được máy chủ. Kiểm tra địa chỉ API và mạng."; else -> it.message ?: "Có lỗi xảy ra." } }
                }
            }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp)) { Text(if (busy) "Đang kết nối…" else if (register) "Tạo tài khoản" else "Đăng nhập", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { register = !register; error = null }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (register) "Đã có tài khoản? Đăng nhập" else "Chưa có tài khoản? Đăng ký", color = Cyan) }
            Text("Tài khoản được xác thực qua máy chủ DK Connect.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun Header(title: String, subtitle: String, accent: Color, action: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold); Text(subtitle, color = Muted, fontSize = 12.sp) }
        action?.invoke()
    }
}

@Composable
fun MomentsScreen(accent: Color, motionEnabled: Boolean, token: String, baseUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(token, baseUrl) { RemoteApi(token, baseUrl) }
    var stories by remember { mutableStateOf<List<DkStory>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    val storyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { try { status = "Đang tải khoảnh khắc…"; api.uploadStory(context, uri); stories = api.stories(); status = "Đã đăng story, tự hết hạn sau 24 giờ." } catch (e: Exception) { status = e.message ?: "Không đăng được story." } }
    }
    LaunchedEffect(token) { try { stories = api.stories() } catch (e: Exception) { status = e.message } }
    Column(Modifier.fillMaxSize()) {
        Header("Khoảnh khắc", "Story của bạn bè · tự hết hạn sau 24 giờ", accent) { IconButton(onClick = { storyPicker.launch(arrayOf("image/*", "video/*")) }) { Icon(Icons.Default.AddCircle, "Đăng story", tint = accent, modifier = Modifier.size(28.dp)) } }
        status?.let { Text(it, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) }
        LazyColumn(contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StoryTile("Bạn", "＋", accent, true, motionEnabled)
                    stories.distinctBy { it.userId }.take(6).forEach { story -> StoryTile(story.name, story.name.take(2).uppercase(), accent, motionEnabled = motionEnabled) }
                }
            }
            if (stories.isEmpty()) item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) { Icon(Icons.Default.AutoAwesome, null, tint = accent, modifier = Modifier.size(30.dp)); Spacer(Modifier.height(8.dp)); Text("Chưa có story mới", fontWeight = FontWeight.Bold); Text("Chạm dấu + để đăng ảnh hoặc video. Story sẽ tự hết hạn sau 24 giờ.", color = Muted, fontSize = 12.sp) }
                }
            }
            items(stories, key = { it.id }) { story ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(13.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Avatar(story.name.take(2).uppercase(), accent); Column(Modifier.padding(start = 10.dp)) { Text(story.name, fontWeight = FontWeight.Bold); Text("Story · 24 giờ", color = Muted, fontSize = 10.sp) } }
                        Spacer(Modifier.height(10.dp))
                        if (story.mediaUrl.substringBefore('?').lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".webm") }) {
                            var videoView by remember(story.id) { mutableStateOf<VideoView?>(null) }
                            var playing by remember(story.id) { mutableStateOf(false) }
                            DisposableEffect(story.id) { onDispose { videoView?.stopPlayback() } }
                            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black).clickable { videoView?.let { if (playing) it.pause() else it.start(); playing = !playing } }) {
                                AndroidView(factory = { ctx -> VideoView(ctx).also { view -> view.setVideoURI(Uri.parse(story.mediaUrl)); view.setOnPreparedListener { player -> player.isLooping = true; videoView = view } } }, modifier = Modifier.fillMaxSize())
                                if (!playing) Icon(Icons.Default.PlayCircle, "Phát video", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(52.dp))
                            }
                        } else AsyncImage(model = story.mediaUrl, contentDescription = "Story của ${story.name}", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(16.dp)))
                        if (story.caption.isNotBlank()) Text(story.caption, color = Color.White, modifier = Modifier.padding(top = 9.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun StoryTile(name: String, initials: String, accent: Color, add: Boolean = false, motionEnabled: Boolean = true) {
    val rotation by animateFloatAsState(targetValue = if (motionEnabled) 360f else 0f, animationSpec = tween(900), label = "ring-rotation")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(67.dp)) {
        Box(Modifier.size(61.dp).rotate(rotation).background(Brush.sweepGradient(listOf(accent, Cyan, Pink, accent)), CircleShape).padding(2.5.dp).background(Panel, CircleShape), Alignment.Center) { Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.rotate(-rotation)) }
        Spacer(Modifier.height(5.dp)); Text(name, color = Muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun Avatar(text: String, color: Color = Violet, online: Boolean = false) {
    Box(Modifier.size(43.dp)) {
        Box(Modifier.fillMaxSize().background(color.copy(.22f), CircleShape), Alignment.Center) { Text(text, color = Color.White, fontWeight = FontWeight.Bold) }
        if (online) Box(Modifier.size(11.dp).align(Alignment.BottomEnd).background(Cyan, CircleShape).padding(2.dp).background(Panel, CircleShape))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(accent: Color, motionEnabled: Boolean, token: String, baseUrl: String, selectedPeer: DkFriend?, realtime: RealtimeClient, onPeer: (DkFriend?) -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember(token, baseUrl) { RemoteApi(token, baseUrl) }
    var friends by remember { mutableStateOf<List<DkFriend>>(emptyList()) }
    val messages = remember { mutableStateListOf<DkMessage>() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var nextBefore by remember { mutableStateOf<String?>(null) }
    var loadingOlder by remember { mutableStateOf(false) }
    var loadedPeerId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var adminCommands by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = LocalContext.current
    var saved by remember { mutableStateOf(prefs(context).getBoolean("chat_saved", true)) }
    var recallTarget by remember { mutableStateOf<DkMessage?>(null) }
    val stickerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selectedPeer != null) scope.launch {
            try {
                error = "Đang tạo sticker…"
                val url = api.uploadSticker(context, uri)
                val item = api.sendMessage(selectedPeer.id, "sticker:$url")
                messages.add(item); error = null
            } catch (e: Exception) { error = e.message ?: "Không tạo được sticker." }
        }
    }
    LaunchedEffect(token) {
        try { friends = api.friends().filter { it.status == "accepted" } }
        catch (e: Exception) { error = e.message ?: "Không tải được cuộc trò chuyện." }
    }
    DisposableEffect(realtime, selectedPeer?.id) {
        val messageNewListener = realtime.on("message:new") { obj ->
            val item = DkMessage(obj.optString("id"), obj.optString("sender_id"), obj.optString("receiver_id"), obj.optString("body"), obj.optString("created_at"), obj.optString("read_at").takeIf { it.isNotBlank() })
            val nearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= messages.lastIndex - 1 } ?: true
            scope.launch { if (selectedPeer?.id == item.senderId || selectedPeer?.id == item.receiverId) { if (messages.none { it.id == item.id }) { messages.add(item); if (nearBottom && messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) } } }
        }
        val messageSentListener = realtime.on("message:sent") { obj ->
            val item = DkMessage(obj.optString("id"), obj.optString("sender_id"), obj.optString("receiver_id"), obj.optString("body"), obj.optString("created_at"), obj.optString("read_at").takeIf { it.isNotBlank() })
            val nearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= messages.lastIndex - 1 } ?: true
            scope.launch { if (messages.none { it.id == item.id }) { messages.add(item); if (nearBottom && messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) } }
        }
        val messageRecalledListener = realtime.on("message:recalled") { obj ->
            val id = obj.optString("id"); val body = obj.optString("body")
            scope.launch { val index = messages.indexOfFirst { it.id == id }; if (index >= 0) messages[index] = messages[index].copy(body = body) }
        }
        val readListener = realtime.on("message:read") { obj ->
            if (obj.optString("by") == selectedPeer?.id) scope.launch {
                val now = java.time.Instant.now().toString()
                messages.indices.forEach { index -> if (messages[index].senderId != selectedPeer.id) messages[index] = messages[index].copy(readAt = now) }
            }
        }
        val typingListener = realtime.on("typing:update") { obj -> scope.launch { if (obj.optString("userId") == selectedPeer?.id) typing = obj.optBoolean("typing") } }
        val presenceListener = realtime.on("presence:update") { obj -> scope.launch { if (obj.optString("userId") == selectedPeer?.id) online = obj.optBoolean("online") } }
        onDispose {
            selectedPeer?.let { realtime.typing(it.id, false) }
            realtime.off("message:new", messageNewListener)
            realtime.off("message:sent", messageSentListener)
            realtime.off("message:recalled", messageRecalledListener)
            realtime.off("message:read", readListener)
            realtime.off("typing:update", typingListener)
            realtime.off("presence:update", presenceListener)
        }
    }
    LaunchedEffect(selectedPeer?.id) {
        val peer = selectedPeer ?: return@LaunchedEffect
        loading = true; error = null; typing = false; loadedPeerId = null; nextBefore = null; loadingOlder = false; messages.clear()
        try {
            val page = api.messagePage(peer.id)
            messages.addAll(page.messages); nextBefore = page.nextBefore; api.markRead(peer.id)
            if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
            loadedPeerId = peer.id
            online = friends.firstOrNull { it.id == peer.id }?.online ?: false
        } catch (e: Exception) { error = e.message ?: "Không tải được lịch sử chat." }
        loading = false
    }
    LaunchedEffect(selectedPeer?.id, loadedPeerId) {
        val peer = selectedPeer ?: return@LaunchedEffect
        if (loadedPeerId != peer.id) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            val cursor = nextBefore
            if (index <= 1 && cursor != null && !loadingOlder) {
                loadingOlder = true
                val oldIndex = listState.firstVisibleItemIndex
                val oldOffset = listState.firstVisibleItemScrollOffset
                try {
                    val page = api.messagePage(peer.id, cursor)
                    if (page.messages.isNotEmpty()) {
                        messages.addAll(0, page.messages)
                        nextBefore = page.nextBefore
                        listState.scrollToItem(oldIndex + page.messages.size, oldOffset)
                    } else nextBefore = null
                } catch (e: Exception) { error = e.message }
                loadingOlder = false
            }
        }
    }

    if (selectedPeer == null) {
        Column(Modifier.fillMaxSize()) {
            Header("Tin nhắn", "Trò chuyện đồng bộ qua máy chủ", accent) { IconButton(onClick = { scope.launch { try { friends = api.friends().filter { it.status == "accepted" }; error = null } catch (e: Exception) { error = e.message } } }) { Icon(Icons.Default.Refresh, null, tint = accent) } }
            error?.let { Text(it, color = Color(0xFFFF8B9A), modifier = Modifier.padding(horizontal = 20.dp)) }
            if (friends.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Chưa có bạn bè. Vào mục Bạn bè để kết nối nhé.", color = Muted) }
            else LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                items(friends, key = { it.id }) { friend ->
                    Row(Modifier.fillMaxWidth().clickable { onPeer(friend) }.padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(friend.name.take(2).uppercase(), accent, friend.online)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(friend.name, fontWeight = FontWeight.Bold); Text(if (friend.online) "Đang hoạt động" else "Chạm để xem lịch sử trò chuyện", color = if (friend.online) Cyan else Muted, fontSize = 11.sp) }
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                    HorizontalDivider(color = Color.White.copy(.06f), modifier = Modifier.padding(start = 75.dp))
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onPeer(null) }) { Icon(Icons.Default.ArrowBack, null) }
                Avatar(selectedPeer.name.take(2).uppercase(), accent, online)
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(selectedPeer.name, fontWeight = FontWeight.Bold); Text(if (typing) "Đang soạn tin nhắn…" else if (online) "Đang hoạt động" else "Ngoại tuyến", color = if (online) Cyan else Muted, fontSize = 10.sp) }
                IconButton(onClick = {}) { Icon(Icons.Default.Call, null, tint = accent) }; IconButton(onClick = {}) { Icon(Icons.Default.Videocam, null, tint = accent) }
            }
            HorizontalDivider(color = Color.White.copy(.07f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDone, null, tint = accent, modifier = Modifier.size(16.dp)); Text("  Lưu lịch sử trò chuyện · đồng bộ máy chủ", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Switch(checked = saved, onCheckedChange = { saved = it; prefs(context).edit().putBoolean("chat_saved", it).apply() })
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
            error?.let { Text(it, color = Color(0xFFFF8B9A), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (loadingOlder) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent) }
                items(messages, key = { it.id }) { msg ->
                    val mine = msg.senderId != selectedPeer.id
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp, bottomStart = if (mine) 19.dp else 5.dp, bottomEnd = if (mine) 5.dp else 19.dp)).background(if (mine) Brush.linearGradient(listOf(accent.copy(.45f), Color(0xFF343050))) else Brush.linearGradient(listOf(Panel, Panel))).combinedClickable(onClick = {}, onLongClick = { if (mine && !msg.body.startsWith("sticker:")) recallTarget = msg }).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            if (msg.body.startsWith("sticker:")) AsyncImage(model = msg.body.removePrefix("sticker:"), contentDescription = "Sticker động", modifier = Modifier.size(148.dp).clip(RoundedCornerShape(12.dp)))
                            else Text(msg.body, color = Color.White, fontSize = 14.sp)
                            Text(if (mine) if (msg.readAt != null) "Đã xem" else "Đã gửi" else "", color = Color.White.copy(.55f), fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                if (typing) item { TypingBubble(accent, motionEnabled) }
            }
            AnimatedVisibility(visible = showCommands, enter = fadeIn() + scaleIn(), exit = fadeOut()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp)) {
                    Text("LỆNH NHANH", color = accent, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    listOf("/help" to "Hướng dẫn lệnh", "/save" to "Lưu lịch sử trên thiết bị", "/stk" to "Tạo sticker từ ảnh/video").plus(adminCommands.map { it to "Lệnh quản trị" }).forEach { (cmd, desc) ->
                        Row(Modifier.fillMaxWidth().clickable { draft = "$cmd "; showCommands = false }.padding(vertical = 6.dp)) { Text(cmd, color = Cyan, fontWeight = FontWeight.Bold); Text("  $desc", color = Muted, fontSize = 11.sp) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(25.dp)).background(Panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { draft += "😊" }) { Icon(Icons.Default.EmojiEmotions, null, tint = accent) }
                BasicTextField(value = draft, onValueChange = { draft = it; showCommands = it.startsWith("/"); realtime.typing(selectedPeer.id, it.isNotBlank()) }, modifier = Modifier.weight(1f).padding(horizontal = 7.dp), textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp), decorationBox = { inner -> if (draft.isEmpty()) Text("Nhắn tin… nhập / để xem lệnh", color = Muted, fontSize = 12.sp); inner() })
                IconButton(onClick = { stickerPicker.launch(arrayOf("image/*", "video/*")) }) { Icon(Icons.Default.AttachFile, null, tint = Muted) }
                IconButton(onClick = { showCommands = !showCommands; if (showCommands) scope.launch { try { val r = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(baseUrl.trimEnd('/') + "/api/admin/commands").header("Authorization", "Bearer $token").get().build()).execute().use { JSONObject(it.body?.string().orEmpty()) }; val a = r.optJSONArray("commands") ?: JSONArray(); adminCommands = (0 until a.length()).map { a.getString(it) } } catch (_: Exception) { adminCommands = emptyList() } } }) { Icon(Icons.Default.AddCircle, null, tint = Muted) }
                IconButton(enabled = draft.isNotBlank(), onClick = {
                    val value = draft.trim(); draft = ""; realtime.typing(selectedPeer.id, false)
                    when {
                        value.equals("/help", true) -> { showCommands = true }
                        value.equals("/save", true) -> { saved = true; prefs(context).edit().putBoolean("chat_saved", true).apply() }
                        value.equals("/antirecall on", true) || value.equals("/antirecall off", true) -> scope.launch {
                            try {
                                val enabled = value.endsWith("on", true)
                                val payload = JSONObject().put("enabled", enabled).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                                val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/admin/settings/anti-recall").header("Authorization", "Bearer $token").post(payload).build()
                                withContext(Dispatchers.IO) { OkHttpClient().newCall(req).execute().use { if (!it.isSuccessful) throw IllegalStateException("Lệnh này chỉ dành cho quản trị viên.") } }
                                error = if (enabled) "Đã bật cấu hình chống thu hồi." else "Đã tắt cấu hình chống thu hồi."
                            } catch (e: Exception) { error = e.message }
                        }
                        value.equals("/stk", true) -> stickerPicker.launch(arrayOf("image/*", "video/*"))
                        value.startsWith("/") -> error = "Lệnh không hợp lệ hoặc bạn không có quyền sử dụng. Nhập /help."
                        else -> scope.launch {
                            try {
                                val clientId = java.util.UUID.randomUUID().toString()
                                val item = if (realtime.isConnected()) {
                                    try {
                                        withTimeout(10_000) {
                                            kotlinx.coroutines.suspendCancellableCoroutine<DkMessage> { cont ->
                                                realtime.send(selectedPeer.id, value, clientId) { ok, message, why ->
                                                    if (ok && message != null && cont.isActive) cont.resume(message) {}
                                                    else if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(why ?: "Máy chủ không xác nhận tin nhắn.")))
                                                }
                                            }
                                        }
                                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                                        api.sendMessage(selectedPeer.id, value, clientId)
                                    }
                                } else api.sendMessage(selectedPeer.id, value, clientId)
                                if (messages.none { it.id == item.id }) { messages.add(item); if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
                            } catch (e: Exception) { error = e.message ?: "Không gửi được tin nhắn." }
                        }
                    }
                }) { Icon(Icons.Default.Send, null, tint = accent) }
            }
            Text("Nhấn giữ tin nhắn của bạn để thu hồi (nếu quản trị viên cho phép).", color = Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 5.dp))
        }
    }
    recallTarget?.let { target ->
        AlertDialog(onDismissRequest = { recallTarget = null }, title = { Text("Thu hồi tin nhắn?") }, text = { Text("Tin nhắn sẽ được thay bằng thông báo đã thu hồi ở mọi thiết bị. Quản trị viên có thể tắt quyền thu hồi.") }, confirmButton = { TextButton(onClick = { scope.launch { try { api.recallMessage(target.id); val index = messages.indexOfFirst { it.id == target.id }; if (index >= 0) messages[index] = messages[index].copy(body = "Tin nhắn đã thu hồi"); error = null } catch (e: Exception) { error = e.message ?: "Không thể thu hồi tin nhắn." }; recallTarget = null } }) { Text("Thu hồi") } }, dismissButton = { TextButton(onClick = { recallTarget = null }) { Text("Huỷ") } })
    }
}

@Composable
fun TypingBubble(accent: Color, motionEnabled: Boolean) {
    Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by animateFloatAsState(targetValue = if (motionEnabled) .95f else .65f, animationSpec = tween(if (motionEnabled) 650 + i * 80 else 0), label = "typing-alpha-$i")
            Box(Modifier.padding(end = 4.dp).size(6.dp).background(accent.copy(alpha = alpha), CircleShape))
        }
        Text("Đang soạn tin nhắn…", color = Muted, fontSize = 11.sp)
    }
}

@Composable
fun FriendsScreen(accent: Color, token: String, baseUrl: String, realtime: RealtimeClient, onChat: (DkFriend) -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember(token, baseUrl) { RemoteApi(token, baseUrl) }
    var query by remember { mutableStateOf("") }
    var friends by remember { mutableStateOf<List<DkFriend>>(emptyList()) }
    var results by remember { mutableStateOf<List<DkFriend>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun refresh() { scope.launch { busy = true; try { friends = api.friends(); message = null } catch (e: Exception) { message = e.message }; busy = false } }
    LaunchedEffect(token) { refresh() }
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length >= 2) { delay(350); try { results = api.searchUsers(term) } catch (e: Exception) { message = e.message } }
        else results = emptyList()
    }
    DisposableEffect(realtime) {
        val presenceListener = realtime.on("presence:update") { obj -> scope.launch { val id = obj.optString("userId"); friends = friends.map { if (it.id == id) it.copy(online = obj.optBoolean("online")) else it } } }
        val friendshipListener = realtime.on("friendship:accepted") { scope.launch { refresh() } }
        onDispose { realtime.off("presence:update", presenceListener); realtime.off("friendship:accepted", friendshipListener) }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Bạn bè", "Kết nối bằng tên hiển thị hoặc DK ID", accent) { IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null, tint = accent) } }
        OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Tìm bạn bằng tên hoặc DK ID") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), singleLine = true)
        message?.let { Text(it, color = Color(0xFFFF8B9A), fontSize = 11.sp, modifier = Modifier.padding(14.dp)) }
        if (results.isNotEmpty()) {
            Text("KẾT QUẢ TÌM KIẾM", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
            results.forEach { user -> Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(user.name.take(2), accent); Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(user.name, fontWeight = FontWeight.SemiBold); Text("DK ID: ${user.id}", color = Muted, fontSize = 10.sp) }; TextButton(onClick = { scope.launch { try { val status = api.requestFriend(user.id); message = if (status == "accepted") "Đã kết bạn!" else "Đã gửi lời mời."; results = emptyList(); refresh() } catch (e: Exception) { message = e.message } } }) { Text("Kết bạn") } } }
        }
        Text("LỜI MỜI VÀ BẠN BÈ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
        LazyColumn(contentPadding = PaddingValues(bottom = 18.dp)) {
            items(friends, key = { it.id }) { friend ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(friend.name.take(2).uppercase(), accent, friend.online)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(friend.name, fontWeight = FontWeight.SemiBold); Text(when { friend.status == "accepted" && friend.online -> "Đang hoạt động"; friend.status == "accepted" -> "Bạn bè · ngoại tuyến"; friend.outgoing -> "Đã gửi lời mời"; else -> "Đang chờ bạn chấp nhận" }, color = if (friend.online) Cyan else Muted, fontSize = 10.sp) }
                    when { friend.status == "accepted" -> IconButton(onClick = { onChat(friend) }) { Icon(Icons.Default.ChatBubbleOutline, null, tint = accent) }; !friend.outgoing -> TextButton(onClick = { scope.launch { try { api.acceptFriend(friend.id); refresh() } catch (e: Exception) { message = e.message } } }) { Text("Chấp nhận") }; else -> Text("Đã gửi", color = Muted, fontSize = 10.sp) }
                }
            }
            if (friends.isEmpty() && !busy) item { Text("Chưa có bạn bè. Hãy tìm người quen ở ô phía trên.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(22.dp)) }
        }
    }
}

@Composable
fun CallsScreen(accent: Color) {
    Column(Modifier.fillMaxSize()) {
        Header("Cuộc gọi", "Nghe giọng nói, nhìn thấy nụ cười", accent)
        Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) { Icon(Icons.Default.VideoCall, null, tint = accent, modifier = Modifier.size(40.dp)); Spacer(Modifier.height(10.dp)); Text("Video call HD", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("Cuộc gọi riêng tư và trò chuyện nhóm.", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(15.dp)); Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Bắt đầu cuộc gọi") }; Text("WebRTC client và TURN server chưa được tích hợp.", color = Muted, fontSize = 10.sp) }
        }
        Text("Gần đây", color = Muted, modifier = Modifier.padding(20.dp)); Text("Chưa có cuộc gọi nào", color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun ProfileScreen(accent: Color, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("Cá nhân", "Không gian riêng của bạn", accent)
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(94.dp).background(Brush.linearGradient(listOf(accent, Cyan, Pink)), CircleShape), Alignment.Center) { Text("DK", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Black) }
            Spacer(Modifier.height(12.dp)); Text("Tài khoản DK Connect", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("Thiết lập hồ sơ sau khi đăng nhập", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(22.dp))
        listOf("Chỉnh sửa hồ sơ" to Icons.Default.Edit, "Giao diện & hình nền" to Icons.Default.Palette, "Quyền riêng tư" to Icons.Default.Security, "Thông báo" to Icons.Default.Notifications, "Thiết bị đã đăng nhập" to Icons.Default.Devices, "Trợ giúp và phản hồi" to Icons.Default.Help).forEach { (label, icon) ->
            Row(Modifier.fillMaxWidth().clickable { if (label == "Giao diện & hình nền") onSettings() }.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = accent); Text(label, modifier = Modifier.weight(1f).padding(start = 14.dp)); Icon(Icons.Default.ChevronRight, null, tint = Muted) }
        }
    }
}

@Composable
fun SettingsDialog(theme: String, background: String, performance: String, apiBaseUrl: String, onApiUrl: (String) -> Unit, onTheme: (String) -> Unit, onBackground: (String) -> Unit, onPerformance: (String) -> Unit, onDismiss: () -> Unit) {
    var serverUrl by remember(apiBaseUrl) { mutableStateOf(apiBaseUrl) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tuỳ biến DK Connect") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MÁY CHỦ KẾT NỐI", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, singleLine = true, label = { Text("Địa chỉ API") }, modifier = Modifier.fillMaxWidth())
            Text("Điện thoại thật phải dùng URL máy chủ có thể truy cập; không dùng 10.0.2.2.", color = Muted, fontSize = 10.sp)
            HorizontalDivider(color = Muted.copy(.25f))
            Text("MÀU CHỦ ĐẠO", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            listOf("Tím cực quang", "Hồng neon", "Xanh băng", "Vàng champagne").forEach { choice -> Row(Modifier.fillMaxWidth().clickable { onTheme(choice) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(15.dp).background(when (choice) { "Hồng neon" -> Pink; "Xanh băng" -> Cyan; "Vàng champagne" -> Color(0xFFE8C77A); else -> Violet }, CircleShape)); Text(choice, Modifier.weight(1f).padding(start = 10.dp)); if (theme == choice) Icon(Icons.Default.CheckCircle, null, tint = Cyan) } }
            HorizontalDivider(color = Muted.copy(.25f)); Text("HÌNH NỀN ỨNG DỤNG", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            listOf("Đêm vũ trụ", "Đen tối giản", "Biển đêm", "Tím mộng").forEach { choice -> Row(Modifier.fillMaxWidth().clickable { onBackground(choice) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text(choice, Modifier.weight(1f)); if (background == choice) Icon(Icons.Default.CheckCircle, null, tint = Cyan) } }
            HorizontalDivider(color = Muted.copy(.25f)); Text("HIỆU NĂNG", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            listOf("Tự động", "Tiết kiệm pin", "Mượt tối đa").forEach { choice -> Row(Modifier.fillMaxWidth().clickable { onPerformance(choice) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(choice); Text(when (choice) { "Tiết kiệm pin" -> "Tắt animation lặp, ưu tiên máy yếu"; "Mượt tối đa" -> "Giữ hiệu ứng chuyển động đầy đủ"; else -> "Cân bằng trải nghiệm và tài nguyên" }, color = Muted, fontSize = 10.sp) }; if (performance == choice) Icon(Icons.Default.CheckCircle, null, tint = Cyan) } }
        }
    }, confirmButton = { TextButton(onClick = { if (serverUrl.startsWith("https://") || serverUrl.startsWith("http://")) onApiUrl(serverUrl); onDismiss() }) { Text("Lưu & hoàn tất") } })
}
