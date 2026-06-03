package com.ebookreader.ui.reader

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebookreader.data.model.Book
import com.ebookreader.tts.TtsState
import com.ebookreader.ui.theme.ReadingTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book,
    onBackClick: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    val chapters by viewModel.chapters.collectAsState()
    val displayItems by viewModel.displayItems.collectAsState()
    val currentSentenceIndex by viewModel.currentSentenceIndex.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showChapterPicker by remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

    // Derive current chapter DIRECTLY from displayItems + flat index (always correct)
    val (currentChapter, currentChapterIndex) = remember(displayItems, currentSentenceIndex, chapters) {
        val displayItem = displayItems.find {
            it is DisplayItem.Sentence && it.flatIndex == currentSentenceIndex
        } as? DisplayItem.Sentence
        if (displayItem != null && displayItem.chapterIndex in chapters.indices) {
            Pair(chapters[displayItem.chapterIndex], displayItem.chapterIndex)
        } else if (chapters.isNotEmpty()) {
            Pair(chapters[0], 0)
        } else {
            Pair(null, 0)
        }
    }

    // Auto-save progress when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveProgress()
        }
    }

    // Auto-scroll: chỉ scroll khi câu đang đọc vượt quá item cuối trên màn hình
    LaunchedEffect(currentSentenceIndex) {
        // Find display index for current flat sentence
        val targetDisplayIdx = displayItems.indexOfFirst {
            it is DisplayItem.Sentence && it.flatIndex == currentSentenceIndex
        }
        if (targetDisplayIdx >= 0) {
            val lastVisible = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (lastVisible != null && targetDisplayIdx > lastVisible.index) {
                lazyListState.animateScrollToItem(targetDisplayIdx)
            }
        }
    }

    LaunchedEffect(book.id) {
        viewModel.loadBook(book)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentChapter?.title ?: book.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                        if (chapters.size > 1) {
                            Text(
                                text = "Chương ${currentChapterIndex + 1}/${chapters.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveProgress()
                        onBackClick()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    IconButton(onClick = { showChapterPicker = true }) {
                        Icon(Icons.Default.List, contentDescription = "Chương")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Cài đặt")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        bottomBar = {
            ReaderBottomBar(
                ttsState = ttsState,
                onPlayPause = {
                    if (ttsState == TtsState.IDLE || ttsState == TtsState.STOPPED) {
                        // Phát từ vị trí đang hiển thị trên màn hình
                        val firstVisibleDisplayIdx = lazyListState.firstVisibleItemIndex
                        val flatIdx = findFlatIndexAtDisplayIndex(displayItems, firstVisibleDisplayIdx)
                        if (flatIdx >= 0) {
                            viewModel.startPlaybackFrom(flatIdx)
                        } else {
                            viewModel.playPause()
                        }
                    } else {
                        viewModel.playPause()
                    }
                },
                onPreviousChapter = {
                    if (currentChapterIndex > 0) viewModel.navigateToChapter(currentChapterIndex - 1)
                },
                onNextChapter = {
                    if (currentChapterIndex < chapters.size - 1) viewModel.navigateToChapter(currentChapterIndex + 1)
                },
                hasPrevious = currentChapterIndex > 0,
                hasNext = currentChapterIndex < chapters.size - 1
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (displayItems.isNotEmpty()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    itemsIndexed(
                        items = displayItems,
                        key = { _, item ->
                            when (item) {
                                is DisplayItem.Header -> "hdr_${item.chapterIndex}"
                                is DisplayItem.Sentence -> "sent_${item.flatIndex}"
                            }
                        }
                    ) { _, item ->
                        when (item) {
                            is DisplayItem.Header -> {
                                // Chapter header with visual separator
                                if (item.chapterIndex > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        top = if (item.chapterIndex > 0) 8.dp else 0.dp,
                                        bottom = 12.dp
                                    )
                                )
                            }
                            is DisplayItem.Sentence -> {
                                val isCurrent = item.flatIndex == currentSentenceIndex
                                        && ttsState == TtsState.PLAYING
                                Text(
                                    text = item.span.text,
                                    style = ReadingTypography.copy(
                                        background = if (isCurrent) highlightColor else Color.Transparent,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }
                    }

                    // Bottom spacer so last sentence isn't hidden by bottom bar
                    item(key = "bottom_spacer") {
                        Spacer(Modifier.height(80.dp))
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    // Chapter picker dialog
    if (showChapterPicker) {
        Dialog(
            onDismissRequest = { showChapterPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.75f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Chọn chương",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { showChapterPicker = false }) {
                            Text("Đóng")
                        }
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp)
                    ) {
                        chapters.forEachIndexed { index, chapter ->
                            TextButton(
                                onClick = {
                                    viewModel.navigateToChapter(index)
                                    showChapterPicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = chapter.title,
                                    fontWeight = if (index == currentChapterIndex) FontWeight.Bold else FontWeight.Normal,
                                    color = if (index == currentChapterIndex)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Settings dialog
    if (showSettings) {
        ReaderSettingsDialog(
            onDismiss = { showSettings = false },
            onServerUrlChange = { url -> viewModel.updateTtsServerUrl(url) }
        )
    }
}

/**
 * Find the flat sentence index from a display (LazyColumn) index.
 */
private fun findFlatIndexAtDisplayIndex(
    displayItems: List<DisplayItem>,
    displayIdx: Int
): Int {
    for (i in displayIdx until displayItems.size) {
        val item = displayItems[i]
        if (item is DisplayItem.Sentence) return item.flatIndex
    }
    // Fallback: find previous sentence
    for (i in displayIdx downTo 0) {
        val item = displayItems[i]
        if (item is DisplayItem.Sentence) return item.flatIndex
    }
    return 0
}

@Composable
fun ReaderBottomBar(
    ttsState: TtsState,
    onPlayPause: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPreviousChapter,
                enabled = hasPrevious
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Chương trước")
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = when (ttsState) {
                            TtsState.PLAYING -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = if (ttsState == TtsState.PLAYING) "Tạm dừng" else "Phát",
                        modifier = Modifier.size(32.dp)
                    )
                }
                // State label
                if (ttsState == TtsState.PLAYING) {
                    Text(
                        text = "Đang đọc...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else if (ttsState == TtsState.PAUSED) {
                    Text(
                        text = "Đã dừng",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            IconButton(
                onClick = onNextChapter,
                enabled = hasNext
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Chương sau")
            }
        }
    }
}

@Composable
fun ReaderSettingsDialog(
    onDismiss: () -> Unit,
    onServerUrlChange: (String) -> Unit
) {
    var serverUrl by remember { mutableStateOf("https://tts.ngtri.io.vn") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cài đặt đọc") },
        text = {
            Column {
                Text(
                    "TTS Server URL",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onServerUrlChange(serverUrl)
                onDismiss()
            }) {
                Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}
