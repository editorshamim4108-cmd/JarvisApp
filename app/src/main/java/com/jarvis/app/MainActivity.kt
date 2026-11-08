package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.app.data.AppDatabase
import com.jarvis.app.data.ConversationMessage
import com.jarvis.app.service.JarvisAccessibilityService
import com.jarvis.app.service.JarvisForegroundService
import com.jarvis.app.service.JarvisForegroundService.JarvisState
import com.jarvis.app.service.OverlayService
import com.jarvis.app.settings.SettingsManager
import com.jarvis.app.ui.theme.BgBlack
import com.jarvis.app.ui.theme.CyberCyan
import com.jarvis.app.ui.theme.JarvisTheme
import com.jarvis.app.ui.theme.NeonPurple
import com.jarvis.app.ui.theme.PanelDark
import com.jarvis.app.ui.theme.StatusGreen
import com.jarvis.app.ui.theme.SurfaceDark
import com.jarvis.app.ui.theme.TextGray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            startJarvisServices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestPermissions()
        startJarvisServices()
        requestBatteryOptimizationExemption()

        setContent {
            JarvisTheme {
                JarvisMainScreen()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @Suppress("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startJarvisServices() {
        // Start foreground service
        val serviceIntent = Intent(this, JarvisForegroundService::class.java).apply {
            action = JarvisForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Overlay service starts only when wake word is detected — not on app launch
    }

    override fun onResume() {
        super.onResume()
        if (JarvisForegroundService.instance == null) {
            startJarvisServices()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun JarvisMainScreen() {
        var currentState by remember { mutableStateOf(JarvisForegroundService.currentState) }
        var heardText by remember { mutableStateOf("") }
        var replyText by remember { mutableStateOf("জি Boss, আমি জার্ভিস। যেকোনো প্রশ্ন করুন বা কমান্ড দিন!") }
        var inputText by remember { mutableStateOf("") }
        var rmsLevel by remember { mutableFloatStateOf(0f) }
        var showSettings by remember { mutableStateOf(false) }
        var showChat by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        val scope = rememberCoroutineScope()

        DisposableEffect(Unit) {
            val stateListener: (JarvisState) -> Unit = { currentState = it }
            val textListener: (String) -> Unit = { heardText = "Boss: \"$it\"" }
            val replyListener: (String) -> Unit = { replyText = it }
            val rmsListener: (Float) -> Unit = { rmsLevel = it }

            JarvisForegroundService.stateListeners.add(stateListener)
            JarvisForegroundService.textListeners.add(textListener)
            JarvisForegroundService.replyListeners.add(replyListener)
            JarvisForegroundService.rmsListeners.add(rmsListener)

            onDispose {
                JarvisForegroundService.stateListeners.remove(stateListener)
                JarvisForegroundService.textListeners.remove(textListener)
                JarvisForegroundService.replyListeners.remove(replyListener)
                JarvisForegroundService.rmsListeners.remove(rmsListener)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF0D0A1E), BgBlack),
                        center = Offset(0.5f, 0.35f),
                        radius = 1200f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Bar
                Spacer(modifier = Modifier.height(48.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (currentState == JarvisState.LISTENING) StatusGreen
                                    else CyberCyan
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "JARVIS",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                when (currentState) {
                                    JarvisState.STANDBY -> "Standby — \"Hey Jarvis\" বলুন"
                                    JarvisState.LISTENING -> "Listening — কথা বলুন Boss"
                                    JarvisState.PROCESSING -> "Thinking..."
                                    JarvisState.SPEAKING -> "Speaking..."
                                },
                                fontSize = 11.sp,
                                color = TextGray
                            )
                        }
                    }
                    Row {
                        IconButton(onClick = { showChat = true }) {
                            Icon(Icons.Default.Chat, "Chat History", tint = CyberCyan)
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, "Settings", tint = CyberCyan)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(0.3f))

                // Orb
                GlowingOrb(
                    isActive = currentState == JarvisState.LISTENING || currentState == JarvisState.SPEAKING,
                    rmsLevel = rmsLevel,
                    onClick = {
                        val service = JarvisForegroundService.instance
                        if (currentState == JarvisState.LISTENING) {
                            service?.stopSpeechRecognition()
                        } else {
                            service?.onWakeWordDetected()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    when (currentState) {
                        JarvisState.LISTENING -> "LISTENING"
                        JarvisState.PROCESSING -> "THINKING"
                        JarvisState.SPEAKING -> "SPEAKING"
                        JarvisState.STANDBY -> "HEY JARVIS"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                    letterSpacing = 3.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Waveform
                WaveformBar(
                    isActive = currentState == JarvisState.LISTENING,
                    rmsLevel = rmsLevel,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(32.dp)
                )

                Spacer(modifier = Modifier.weight(0.2f))

                // Heard + Reply text
                if (heardText.isNotEmpty()) {
                    Text(
                        heardText,
                        fontSize = 12.sp,
                        color = CyberCyan,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text(
                    "\"$replyText\"",
                    fontSize = 14.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.weight(0.3f))

                // Input bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text("প্রশ্ন বা কমান্ড লিখুন...", fontSize = 13.sp, color = TextGray)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = NeonPurple.copy(alpha = 0.5f),
                            focusedContainerColor = PanelDark,
                            unfocusedContainerColor = PanelDark,
                            cursorColor = CyberCyan,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank()) {
                                val text = inputText.trim()
                                inputText = ""
                                focusManager.clearFocus()
                                val intent = Intent(this@MainActivity, JarvisForegroundService::class.java).apply {
                                    action = JarvisForegroundService.ACTION_PROCESS_TEXT
                                    putExtra(JarvisForegroundService.EXTRA_TEXT, text)
                                }
                                startService(intent)
                            }
                        }),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(NeonPurple, Color(0xFF0066FF)))
                            )
                            .clickable {
                                if (inputText.isNotBlank()) {
                                    val text = inputText.trim()
                                    inputText = ""
                                    focusManager.clearFocus()
                                    val intent = Intent(this@MainActivity, JarvisForegroundService::class.java).apply {
                                        action = JarvisForegroundService.ACTION_PROCESS_TEXT
                                        putExtra(JarvisForegroundService.EXTRA_TEXT, text)
                                    }
                                    startService(intent)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowForward, "Send", tint = Color.White)
                    }
                }
            }

            // Chat Bottom Sheet
            if (showChat) {
                ChatBottomSheet(onDismiss = { showChat = false })
            }

            // Settings Bottom Sheet
            if (showSettings) {
                SettingsBottomSheet(onDismiss = { showSettings = false })
            }
        }
    }

    @Composable
    fun GlowingOrb(isActive: Boolean, rmsLevel: Float, onClick: () -> Unit) {
        val transition = rememberInfiniteTransition(label = "orb")
        val scale by transition.animateFloat(
            initialValue = if (isActive) 1f else 0.97f,
            targetValue = if (isActive) 1.12f else 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (isActive) 800 else 3200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "orbScale"
        )

        val glowAlpha by transition.animateFloat(
            initialValue = if (isActive) 0.3f else 0.1f,
            targetValue = if (isActive) 0.8f else 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (isActive) 600 else 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowAlpha"
        )

        val orbSize = 220.dp

        Box(
            modifier = Modifier
                .size(orbSize)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            // Outer glow ring (blinks)
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = if (isActive) StatusGreen.copy(alpha = glowAlpha)
                            else CyberCyan.copy(alpha = glowAlpha),
                    radius = size.minDimension / 2
                )
            }

            // Middle ring
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                drawCircle(
                    color = CyberCyan.copy(alpha = 0.25f),
                    radius = size.minDimension / 2,
                    style = Stroke(width = 2f)
                )
            }

            // Inner ring
            Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                drawCircle(
                    color = NeonPurple.copy(alpha = 0.3f),
                    radius = size.minDimension / 2,
                    style = Stroke(width = 1.5f)
                )
            }

            // Orb core
            Box(
                modifier = Modifier
                    .size(orbSize * 0.6f * scale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            if (isActive)
                                listOf(CyberCyan, Color(0xFF0066FF), NeonPurple, Color(0xFF120924))
                            else
                                listOf(Color(0xFF371F66), Color(0xFF161E3D), Color(0xFF090A12))
                        )
                    )
                    .drawBehind {
                        if (isActive) {
                            drawCircle(
                                color = CyberCyan.copy(alpha = 0.4f),
                                radius = size.minDimension / 2 + 24f
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Microphone",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }

    @Composable
    fun WaveformBar(isActive: Boolean, rmsLevel: Float, modifier: Modifier = Modifier) {
        val transition = rememberInfiniteTransition(label = "wave")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 6.28f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wavePhase"
        )

        Canvas(modifier = modifier) {
            val barCount = 16
            val barWidth = 4f
            val gap = (size.width - barCount * barWidth) / (barCount - 1)
            val maxHeight = size.height

            for (i in 0 until barCount) {
                val x = i * (barWidth + gap)
                val h = if (isActive) {
                    val wave = sin(phase + i * 0.5f).toFloat()
                    (maxHeight * 0.3f + maxHeight * 0.7f * ((wave + 1f) / 2f)).coerceAtLeast(6f)
                } else {
                    6f
                }
                val y = (maxHeight - h) / 2

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(CyberCyan, NeonPurple)
                    ),
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ChatBottomSheet(onDismiss: () -> Unit) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val messages = remember { mutableStateListOf<ConversationMessage>() }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            val db = AppDatabase.getInstance(this@MainActivity)
            val recent = withContext(Dispatchers.IO) { db.messageDao().getRecent(50) }
            messages.clear()
            messages.addAll(recent.reversed())
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = BgBlack
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    "Conversation History",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (messages.isEmpty()) {
                    Text("কোনো কথোপকথন নেই।", color = TextGray, fontSize = 13.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.height(400.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(messages) { msg ->
                            val isUser = msg.role == "user"
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .background(
                                            if (isUser) Color(0xFF1A1A3A) else SurfaceDark,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .border(
                                            0.5.dp,
                                            if (isUser) CyberCyan.copy(alpha = 0.3f) else NeonPurple.copy(alpha = 0.2f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        if (isUser) "Boss" else "JARVIS",
                                        fontSize = 10.sp,
                                        color = if (isUser) CyberCyan else StatusGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        msg.content,
                                        fontSize = 12.sp,
                                        color = Color.White,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsBottomSheet(onDismiss: () -> Unit) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var selectedProvider by remember { mutableStateOf(settings.getAiProvider()) }
        var aiApiKey by remember { mutableStateOf(settings.getAiApiKey()) }
        var key1 by remember { mutableStateOf(settings.getKey1()) }
        var key2 by remember { mutableStateOf(settings.getKey2()) }
        var speed by remember { mutableFloatStateOf(settings.getSpeed()) }
        var wakeWordEnabled by remember { mutableStateOf(settings.isWakeWordEnabled()) }
        var overlayEnabled by remember { mutableStateOf(settings.isOverlayEnabled()) }
        var autoDeleteReminders by remember { mutableStateOf(settings.isAutoDeleteReminders()) }

        ModalBottomSheet(
            onDismissRequest = {
                // Save on dismiss
                settings.setAiProvider(selectedProvider)
                settings.setAiApiKey(aiApiKey)
                settings.setKey1(key1)
                settings.setKey2(key2)
                settings.setSpeed(speed)
                settings.setWakeWordEnabled(wakeWordEnabled)
                settings.setOverlayEnabled(overlayEnabled)
                settings.setAutoDeleteReminders(autoDeleteReminders)
                onDismiss()
            },
            sheetState = sheetState,
            containerColor = BgBlack
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "JARVIS Settings",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan,
                        fontSize = 14.sp
                    )
                }

                // AI Provider
                item {
                    SettingsCard("AI Brain") {
                        val providers = SettingsManager.AiProvider.entries
                        val currentProvider = try {
                            SettingsManager.AiProvider.valueOf(selectedProvider)
                        } catch (_: Exception) {
                            SettingsManager.AiProvider.FREE
                        }

                        providers.forEach { p ->
                            val isSelected = p.name == selectedProvider
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(
                                        if (isSelected) NeonPurple.copy(alpha = 0.15f)
                                        else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        if (isSelected) 1.dp else 0.5.dp,
                                        if (isSelected) CyberCyan else Color(0xFF333333),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { selectedProvider = p.name }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        p.label,
                                        fontSize = 12.sp,
                                        color = if (isSelected) Color.White else TextGray,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(p.hint, fontSize = 9.sp, color = TextGray)
                                }
                                if (isSelected) {
                                    Text("✓", color = StatusGreen, fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (currentProvider != SettingsManager.AiProvider.FREE) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingsTextField("API Key", aiApiKey) { aiApiKey = it }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (aiApiKey.isNotBlank()) "✓ Key saved — ${currentProvider.label} active"
                                else "⚠ Key দিন, না হলে Free AI চলবে",
                                fontSize = 10.sp,
                                color = if (aiApiKey.isNotBlank()) StatusGreen else Color(0xFFFF6B6B),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Voice Engine
                item {
                    SettingsCard("Voice Engine (3-Element Failover)") {
                        SettingsTextField("ElevenLabs Key #1", key1) { key1 = it }
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsTextField("ElevenLabs Key #2", key2) { key2 = it }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Element 3: Free Voice", fontSize = 11.sp, color = CyberCyan)
                            Text("UNLIMITED", fontSize = 10.sp, color = StatusGreen,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // Voice Speed
                item {
                    SettingsCard("Voice Speed") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Speed", fontSize = 12.sp, color = TextGray)
                            Text("${String.format("%.2f", speed)}x", fontSize = 12.sp, color = CyberCyan,
                                fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = speed,
                            onValueChange = { speed = it },
                            valueRange = 0.8f..1.3f,
                            steps = 9,
                            colors = SliderDefaults.colors(
                                thumbColor = CyberCyan,
                                activeTrackColor = CyberCyan
                            )
                        )
                    }
                }

                // Toggles
                item {
                    SettingsCard("Features") {
                        SettingsToggle("Wake word (Hey Jarvis)", wakeWordEnabled) { wakeWordEnabled = it }
                        SettingsToggle("Floating overlay", overlayEnabled) { overlayEnabled = it }
                        SettingsToggle("Auto-delete reminders", autoDeleteReminders) { autoDeleteReminders = it }
                    }
                }

                // Permissions
                item {
                    SettingsCard("Permissions") {
                        PermissionRow("Accessibility Service", JarvisAccessibilityService.isRunning()) {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        }
                        PermissionRow("Overlay Permission", Settings.canDrawOverlays(this@MainActivity)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }

    @Composable
    fun SettingsCard(title: String, content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .border(0.5.dp, NeonPurple.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = StatusGreen
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }

    @Composable
    fun SettingsTextField(label: String, value: String, onValueChange: (String) -> Unit) {
        Text(label, fontSize = 10.sp, color = TextGray)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = CyberCyan
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = Color(0xFF333333),
                focusedContainerColor = BgBlack,
                unfocusedContainerColor = BgBlack,
            ),
            singleLine = true
        )
    }

    @Composable
    fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, color = Color.White)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StatusGreen,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }
    }

    @Composable
    fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(onClick = onClick),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, color = Color.White)
            Text(
                if (granted) "Enabled" else "Tap to enable",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (granted) StatusGreen else Color(0xFFFF6B6B)
            )
        }
    }
}
