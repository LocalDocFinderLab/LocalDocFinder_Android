package com.example.ui.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.R
import com.example.data.repository.DocumentRepository
import com.example.engine.SampleDocumentGenerator
import com.example.engine.SearchMode
import com.example.engine.SearchResult
import com.example.ui.components.DocumentPreviewSheet
import com.example.ui.components.GlossyDocumentPreviewBottomSheet
import com.example.ui.theme.MyApplicationTheme
import com.example.widget.DocuVectorWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlossySearchOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startVoice = intent.getBooleanExtra(DocuVectorWidget.EXTRA_START_VOICE, false)
        val initialQuery = intent.getStringExtra(DocuVectorWidget.EXTRA_QUERY) ?: ""

        setContent {
            MyApplicationTheme(darkTheme = true) {
                GlossySearchOverlayScreen(
                    initialQuery = initialQuery,
                    startVoiceInitially = startVoice,
                    onDismiss = { finish() },
                    onOpenMainActivity = { query ->
                        val mainIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(DocuVectorWidget.EXTRA_QUERY, query)
                        }
                        startActivity(mainIntent)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun GlossySearchOverlayScreen(
    initialQuery: String,
    startVoiceInitially: Boolean,
    onDismiss: () -> Unit,
    onOpenMainActivity: (String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { DocumentRepository(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }

    // SharedPreferences for Glass Opacity customization
    val prefs = remember { context.getSharedPreferences("localdoc_glass_prefs", Context.MODE_PRIVATE) }
    var glassOpacity by remember {
        mutableFloatStateOf(prefs.getFloat("glass_opacity", 0.65f))
    }

    var showOpacityControls by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf(initialQuery) }
    var searchMode by remember { mutableStateOf(SearchMode.HYBRID) }
    var selectedFilterType by remember { mutableStateOf("All") }

    var isSearching by remember { mutableStateOf(false) }
    var rawResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchLatencyMs by remember { mutableLongStateOf(0L) }
    var totalIndexedChunks by remember { mutableStateOf(0) }

    var previewResult by remember { mutableStateOf<SearchResult?>(null) }
    var isListeningVoice by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }

    // Speech Recognizer launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListeningVoice = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenTexts = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val topSpoken = spokenTexts?.firstOrNull()
            if (!topSpoken.isNullOrBlank()) {
                query = topSpoken
            }
        }
    }

    fun launchVoiceSearch() {
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search local documents…")
        }
        val isSpeechAvailable = speechIntent.resolveActivity(context.packageManager) != null
        if (isSpeechAvailable) {
            try {
                isListeningVoice = true
                speechLauncher.launch(speechIntent)
            } catch (_: Exception) {
                isListeningVoice = false
                showVoiceDialog = true
            }
        } else {
            showVoiceDialog = true
        }
    }

    // Trigger voice recognition automatically if started via widget voice button
    LaunchedEffect(startVoiceInitially) {
        if (startVoiceInitially) {
            delay(200)
            launchVoiceSearch()
        } else {
            delay(150)
            try {
                searchFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    // Load initial counts
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val db = com.example.data.local.AppDatabase.getInstance(context)
                totalIndexedChunks = db.documentChunkDao().getTotalChunksCountDirect()
            } catch (_: Exception) {}
        }
    }

    // Debounced search logic
    var searchJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(query, searchMode) {
        searchJob?.cancel()
        if (query.isBlank()) {
            isSearching = true
            searchJob = scope.launch(Dispatchers.IO) {
                val allDocs = try {
                    repository.getAllDocuments(null, com.example.engine.SearchSortOrder.RELEVANCE)
                } catch (_: Exception) {
                    emptyList()
                }
                withContext(Dispatchers.Main) {
                    rawResults = allDocs
                    searchLatencyMs = 0L
                    isSearching = false
                }
            }
        } else {
            delay(120) // debounce
            isSearching = true
            val startTime = System.currentTimeMillis()
            searchJob = scope.launch(Dispatchers.IO) {
                val results = try {
                    repository.search(query, searchMode, topK = 50)
                } catch (_: Exception) {
                    emptyList()
                }
                val duration = System.currentTimeMillis() - startTime
                withContext(Dispatchers.Main) {
                    rawResults = results
                    searchLatencyMs = duration
                    isSearching = false
                    if (query.isNotBlank()) {
                        repository.recordSearchQuery(query, searchMode, results.size)
                    }
                }
            }
        }
    }

    // Filter results by selected extension
    val displayedResults = remember(rawResults, selectedFilterType) {
        if (selectedFilterType == "All") {
            rawResults
        } else {
            rawResults.filter {
                it.fileExtension.equals(selectedFilterType, ignoreCase = true)
            }
        }
    }

    val availableExtensions = remember(rawResults) {
        listOf("All") + rawResults.map { it.fileExtension }.filter { it.isNotBlank() }.distinct()
    }

    BackHandler {
        if (previewResult != null) {
            previewResult = null
        } else {
            onDismiss()
        }
    }

    // Full screen overlay layout over the home screen
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color(0xFF060913).copy(alpha = (glassOpacity * 0.75f).coerceIn(0.15f, 0.95f))
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                // Tapping outer backdrop dismisses overlay back to home screen
                onDismiss()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    // Swallow clicks inside the container
                    focusManager.clearFocus()
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Top Quick Glass Opacity Settings Banner (Collapsible)
            AnimatedVisibility(
                visible = showOpacityControls,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                GlossyGlassCustomizerCard(
                    currentOpacity = glassOpacity,
                    onOpacityChanged = { newOpacity ->
                        glassOpacity = newOpacity
                        prefs.edit().putFloat("glass_opacity", newOpacity).apply()
                    },
                    onClose = { showOpacityControls = false }
                )
            }

            // Google-Bar-Style Glossy Search Capsule
            GlossySearchBarPill(
                query = query,
                onQueryChange = { query = it },
                onClear = { query = "" },
                isSearching = isSearching,
                glassOpacity = glassOpacity,
                focusRequester = searchFocusRequester,
                isVoiceActive = isListeningVoice,
                onVoiceClick = { launchVoiceSearch() },
                onCloseClick = onDismiss,
                onOpenFullApp = { onOpenMainActivity(query) },
                onToggleOpacitySettings = { showOpacityControls = !showOpacityControls }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-bar: Search Modes, Filter Chips, Latency & Count
            GlossyControlsBar(
                searchMode = searchMode,
                onModeSelected = { searchMode = it },
                selectedFilter = selectedFilterType,
                availableExtensions = availableExtensions,
                onFilterSelected = { selectedFilterType = it },
                resultsCount = displayedResults.size,
                latencyMs = searchLatencyMs,
                glassOpacity = glassOpacity
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Results List on Glossy Display with subtle scale and layout transitions
            val resultsExpansionScale by animateFloatAsState(
                targetValue = if (displayedResults.isNotEmpty()) 1.0f else 0.98f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                label = "glossy_results_scale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = 380f
                        )
                    )
                    .graphicsLayer {
                        scaleX = resultsExpansionScale
                        scaleY = resultsExpansionScale
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Color(0xFF0F172A).copy(alpha = (glassOpacity * 0.65f).coerceIn(0.12f, 0.95f))
                    )
                    .border(
                        BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.25f)),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                AnimatedContent(
                    targetState = when {
                        isSearching && displayedResults.isEmpty() -> 0
                        displayedResults.isEmpty() -> 1
                        else -> 2
                    },
                    transitionSpec = {
                        (fadeIn(animationSpec = spring(stiffness = 380f)) +
                         scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)))
                            .togetherWith(
                                fadeOut(animationSpec = spring(stiffness = 380f)) +
                                scaleOut(targetScale = 0.98f, animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f))
                            )
                    },
                    label = "results_expansion_transition"
                ) { state ->
                    when (state) {
                        0 -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF38BDF8),
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Running offline hybrid vector search…",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                        1 -> {
                            GlossyEmptyState(
                                query = query,
                                totalChunks = totalIndexedChunks,
                                onSeedSamples = {
                                    scope.launch(Dispatchers.IO) {
                                        isSearching = true
                                        repository.createAndIndexSampleKnowledgeBase { _, _, _, _ -> }
                                        val allDocs = repository.getAllDocuments(null, com.example.engine.SearchSortOrder.RELEVANCE)
                                        val db = com.example.data.local.AppDatabase.getInstance(context)
                                        val count = db.documentChunkDao().getTotalChunksCountDirect()
                                        withContext(Dispatchers.Main) {
                                            rawResults = allDocs
                                            totalIndexedChunks = count
                                            isSearching = false
                                            DocuVectorWidget.updateAllWidgets(context)
                                        }
                                    }
                                },
                                onQuickVoiceChip = { chip ->
                                    query = chip
                                }
                            )
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("glossy_search_results_list"),
                                contentPadding = PaddingValues(vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(displayedResults, key = { "${it.chunkId}_${it.fileUri}" }) { item ->
                                    Box(
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = spring(0.82f, 380f),
                                            placementSpec = spring(0.82f, 380f),
                                            fadeOutSpec = spring(0.82f, 380f)
                                        )
                                    ) {
                                        GlossySearchResultCard(
                                            result = item,
                                            searchQuery = query,
                                            glassOpacity = glassOpacity,
                                            onPreviewClick = { previewResult = item },
                                            onOpenClick = {
                                                try {
                                                    val openIntent = Intent(Intent.ACTION_VIEW).apply {
                                                        data = Uri.parse(item.fileUri)
                                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(openIntent)
                                                } catch (_: Exception) {
                                                    onOpenMainActivity(item.fileName)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // In-Overlay Document Content Preview Bottom Sheet (Triggers when any search result is selected)
        previewResult?.let { result ->
            GlossyDocumentPreviewBottomSheet(
                result = result,
                resultsList = displayedResults,
                onSelectResult = { previewResult = it },
                repository = repository,
                glassOpacity = glassOpacity,
                searchQuery = query,
                onDismiss = { previewResult = null }
            )
        }

        // Voice Search Modal Sheet (Voice dictation, keyboard voice IME, or simulated speech queries)
        GlossyVoiceSearchSheet(
            show = showVoiceDialog,
            onDismiss = { showVoiceDialog = false },
            onQuerySpoken = { spokenQuery ->
                query = spokenQuery
            },
            onUseKeyboard = {
                scope.launch {
                    delay(150)
                    try {
                        searchFocusRequester.requestFocus()
                    } catch (_: Exception) {}
                }
            },
            glassOpacity = glassOpacity
        )
    }
}

/**
 * Interactive Voice Search Sheet for speech recognition, keyboard IME, and quick voice shortcuts.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GlossyVoiceSearchSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onQuerySpoken: (String) -> Unit,
    onUseKeyboard: () -> Unit,
    glassOpacity: Float
) {
    if (!show) return

    val sampleQueries = listOf(
        "Find meeting notes",
        "Search financial statements",
        "LiteRT neural vector search",
        "Show recent PDF documents",
        "Contract agreement terms"
    )

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A).copy(alpha = (glassOpacity * 0.95f).coerceIn(0.4f, 0.98f)),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF818CF8).copy(alpha = 0.5f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Voice Wave Pulse Icon
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.18f))
                    .border(2.dp, Color(0xFF38BDF8).copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_voice_mic),
                    contentDescription = "Voice Search Active",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Voice Search",
                color = Color(0xFFF8FAFC),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Dictate with keyboard microphone or tap a voice prompt:",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Use Keyboard Voice Typing action
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        onDismiss()
                        onUseKeyboard()
                    },
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.8f),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_voice_mic),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Use Keyboard Voice Typing (Mic Key)",
                            color = Color(0xFFF1F5F9),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Opens keyboard with real-time speech-to-text dictation",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Spoken Query Shortcuts:",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sampleQueries.forEach { sample ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onQuerySpoken(sample)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🎙️", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = sample,
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Google Search Bar replica with high-gloss styling:
 * - Symbol on left
 * - Search input in center
 * - Voice search and Opacity customizer on right
 */
@Composable
fun GlossySearchBarPill(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    isSearching: Boolean,
    glassOpacity: Float,
    focusRequester: FocusRequester,
    isVoiceActive: Boolean,
    onVoiceClick: () -> Unit,
    onCloseClick: () -> Unit,
    onOpenFullApp: () -> Unit,
    onToggleOpacitySettings: () -> Unit
) {
    val pillBackground = Brush.horizontalGradient(
        listOf(
            Color(0xFF1E293B).copy(alpha = (0.92f * glassOpacity).coerceIn(0.20f, 0.98f)),
            Color(0xFF0F172A).copy(alpha = (0.95f * glassOpacity).coerceIn(0.25f, 0.98f))
        )
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(
                elevation = (12 * glassOpacity).dp,
                shape = RoundedCornerShape(29.dp),
                ambientColor = Color(0xFF38BDF8),
                spotColor = Color(0xFF6366F1)
            ),
        shape = RoundedCornerShape(29.dp),
        color = Color.Transparent,
        border = BorderStroke(1.5.dp, Color(0xFF818CF8).copy(alpha = 0.55f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pillBackground)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left End: LocalDoc Finder Symbol
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable { onOpenFullApp() }
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_localdoc_symbol),
                        contentDescription = "LocalDoc Finder",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Center: Text input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search local documents…",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("glossy_search_input"),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color(0xFFF8FAFC),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(Color(0xFF38BDF8)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { /* Search runs dynamically */ })
                    )
                }

                // Clear button
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Glass display customizer toggle
                IconButton(
                    onClick = onToggleOpacitySettings,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Opacity,
                        contentDescription = "Glass Opacity",
                        tint = Color(0xFF818CF8),
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Right End: Voice Search Mic Button
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isVoiceActive) Color(0xFFEF4444).copy(alpha = 0.35f)
                            else Color(0xFF38BDF8).copy(alpha = 0.18f)
                        )
                        .border(
                            1.dp,
                            if (isVoiceActive) Color(0xFFEF4444) else Color(0xFF38BDF8).copy(alpha = 0.5f),
                            CircleShape
                        )
                        .clickable { onVoiceClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_voice_mic),
                        contentDescription = "Voice Search",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Close overlay button (back to home screen)
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close overlay",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Interactive Glass Display Customizer:
 * User can switch between See-Through, Translucent, and Opaque, or slide to exact opacity.
 */
@Composable
fun GlossyGlassCustomizerCard(
    currentOpacity: Float,
    onOpacityChanged: (Float) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E293B).copy(alpha = 0.95f),
        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Opacity,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Glass Display Customization",
                        color = Color(0xFFF1F5F9),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close settings",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 3 Quick Presets: See-Through (30%), Translucent (65%), Opaque (95%)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassPresetChip(
                    title = "See-Through",
                    subtitle = "30%",
                    isSelected = currentOpacity in 0.20f..0.45f,
                    onClick = { onOpacityChanged(0.30f) },
                    modifier = Modifier.weight(1f)
                )

                GlassPresetChip(
                    title = "Translucent",
                    subtitle = "65%",
                    isSelected = currentOpacity in 0.46f..0.80f,
                    onClick = { onOpacityChanged(0.65f) },
                    modifier = Modifier.weight(1f)
                )

                GlassPresetChip(
                    title = "Opaque",
                    subtitle = "95%",
                    isSelected = currentOpacity > 0.80f,
                    onClick = { onOpacityChanged(0.95f) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Granular Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Clear",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                Slider(
                    value = currentOpacity,
                    onValueChange = onOpacityChanged,
                    valueRange = 0.15f..1.0f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF6366F1),
                        inactiveTrackColor = Color(0xFF334155)
                    )
                )
                Text(
                    text = "${(currentOpacity * 100).toInt()}%",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun GlassPresetChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.25f)
                else Color(0xFF0F172A).copy(alpha = 0.6f)
            )
            .border(
                1.dp,
                if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = if (isSelected) Color(0xFF38BDF8) else Color(0xFFE2E8F0),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = Color(0xFF94A3B8),
                fontSize = 9.sp
            )
        }
    }
}

@Composable
fun GlossyControlsBar(
    searchMode: SearchMode,
    onModeSelected: (SearchMode) -> Unit,
    selectedFilter: String,
    availableExtensions: List<String>,
    onFilterSelected: (String) -> Unit,
    resultsCount: Int,
    latencyMs: Long,
    glassOpacity: Float
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Mode & Extension Filters Row
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Modes
            items(SearchMode.values()) { mode ->
                val isSelected = mode == searchMode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) Color(0xFF6366F1).copy(alpha = 0.35f)
                            else Color(0xFF1E293B).copy(alpha = 0.5f * glassOpacity)
                        )
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFF818CF8) else Color(0xFF334155).copy(alpha = 0.6f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onModeSelected(mode) }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = when (mode) {
                            SearchMode.HYBRID -> "⚡ Hybrid"
                            SearchMode.VECTOR -> "🧠 Semantic"
                            SearchMode.KEYWORD -> "🔤 Keyword"
                        },
                        color = if (isSelected) Color(0xFFA5B4FC) else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // Divider or file type filter chips
            items(availableExtensions) { ext ->
                val isSelected = ext.equals(selectedFilter, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.3f)
                            else Color(0xFF1E293B).copy(alpha = 0.4f * glassOpacity)
                        )
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155).copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onFilterSelected(ext) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (ext == "All") "All Types" else ".${ext.uppercase(Locale.ROOT)}",
                        color = if (isSelected) Color(0xFF38BDF8) else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Results summary status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (resultsCount > 0) "$resultsCount documents found" else "Ready to search",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )

            if (latencyMs > 0) {
                Text(
                    text = "⚡ ${latencyMs}ms • 100% Offline",
                    color = Color(0xFF34D399),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun GlossySearchResultCard(
    result: SearchResult,
    searchQuery: String = "",
    glassOpacity: Float,
    onPreviewClick: () -> Unit,
    onOpenClick: () -> Unit
) {
    var cardAppeared by remember { mutableStateOf(false) }
    var isSnippetExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cardAppeared = true
    }

    val cardScale by animateFloatAsState(
        targetValue = if (cardAppeared) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 400f),
        label = "glossy_card_scale"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (cardAppeared) 1f else 0.65f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
        label = "glossy_card_alpha"
    )

    val scorePercent = (result.cosineSimilarity * 100).toInt().coerceIn(1, 100)
    val scoreColor = when {
        scorePercent >= 80 -> Color(0xFF34D399) // Emerald
        scorePercent >= 55 -> Color(0xFF38BDF8) // Cyan
        scorePercent >= 40 -> Color(0xFFFBBF24) // Amber
        else -> Color(0xFF94A3B8)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
                alpha = cardAlpha
            }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.80f,
                    stiffness = 380f
                )
            )
            .clickable { onPreviewClick() },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E293B).copy(alpha = (0.75f * glassOpacity).coerceIn(0.20f, 0.95f)),
        border = BorderStroke(1.dp, Color(0xFF818CF8).copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header: Icon, Title, Similarity Score Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File type icon badge
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF38BDF8).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = result.fileExtension.take(3).uppercase(Locale.ROOT),
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Title and path
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.fileName,
                        color = Color(0xFFF8FAFC),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Chunk #${result.chunkIndex + 1}",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                }

                // Match score badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(scoreColor.copy(alpha = 0.18f))
                        .border(1.dp, scoreColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$scorePercent% match",
                        color = scoreColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Snippet with query highlights and expandable toggle
            val displayText = if (isSnippetExpanded) result.chunkText else result.snippet
            Text(
                text = buildHighlightedSnippet(displayText, result.highlightedTerms, searchQuery),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = if (isSnippetExpanded) 14 else 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons: Expand/Collapse Snippet, Preview and Open
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Expand / Collapse snippet toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.5f))
                        .clickable { isSnippetExpanded = !isSnippetExpanded }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isSnippetExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isSnippetExpanded) "Collapse" else "Expand",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (isSnippetExpanded) "Collapse" else "Expand",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF334155).copy(alpha = 0.6f))
                            .clickable { onPreviewClick() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Preview",
                                tint = Color(0xFF93C5FD),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Preview",
                                color = Color(0xFF93C5FD),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable { onOpenClick() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Launch,
                                contentDescription = "Open",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Open",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlossyEmptyState(
    query: String,
    totalChunks: Int,
    onSeedSamples: () -> Unit,
    onQuickVoiceChip: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 380.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_localdoc_symbol),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(54.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (query.isNotBlank()) "No matching documents found" else "Offline Search Ready",
                color = Color(0xFFF1F5F9),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (query.isNotBlank()) {
                    "Try different keywords or speak using the voice search mic above."
                } else if (totalChunks == 0) {
                    "No documents indexed yet. Tap below to generate instant test documents and test hybrid vector search right now!"
                } else {
                    "Speak or type to search across $totalChunks indexed document chunks offline."
                },
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 17.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (totalChunks == 0) {
                ElevatedButton(
                    onClick = onSeedSamples,
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = Color(0xFF6366F1),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("⚡ Load Sample Documents (Instant 100 docs)")
                }
            } else {
                Text(
                    text = "Quick Voice Queries:",
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("contract", "invoice", "chat", "project").forEach { chip ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                                .clickable { onQuickVoiceChip(chip) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "🗣 $chip",
                                color = Color(0xFF93C5FD),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds an annotated string with highlighted terms in electric cyan.
 */
fun buildHighlightedSnippet(
    snippet: String,
    highlightedTerms: List<String>,
    query: String? = null
): androidx.compose.ui.text.AnnotatedString {
    return com.example.engine.KeywordHighlighter.highlightText(
        text = snippet,
        keywords = highlightedTerms,
        query = query,
        highlightColor = Color(0xFF38BDF8),
        highlightBgColor = Color(0xFF38BDF8).copy(alpha = 0.28f),
        baseTextColor = Color(0xFFCBD5E1)
    ).annotatedString
}
