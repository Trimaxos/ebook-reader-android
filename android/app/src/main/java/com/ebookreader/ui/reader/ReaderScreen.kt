package com.ebookreader.ui.reader

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val currentChapter by viewModel.currentChapter.collectAsState()
    val currentChapterIndex by viewModel.currentChapterIndex.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val sentences by viewModel.sentences.collectAsState()
    val currentSentenceIndex by viewModel.currentSentenceIndex.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showChapterPicker by remember { mutableStateOf(false) }

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
                    viewModel.playPause()
                    if (ttsState != TtsState.PLAYING) viewModel.saveProgress()
                },
                onStop = {
                    viewModel.stop()
                    viewModel.saveProgress()
                },
                onPreviousChapter = {
                    if (currentChapterIndex > 0) viewModel.loadChapter(currentChapterIndex - 1)
                },
                onNextChapter = {
                    if (currentChapterIndex < chapters.size - 1) viewModel.loadChapter(currentChapterIndex + 1)
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
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { /* handled by threshold below */ },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                        }
                    )
                }
        ) {
            currentChapter?.let { chapter ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Chapter title
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Content with sentence highlighting
                    if (sentences.isNotEmpty()) {
                        BuildAnnotatedStringWithHighlight(
                            sentences = sentences,
                            currentIndex = currentSentenceIndex,
                            isPlaying = ttsState == TtsState.PLAYING
                        )
                    } else {
                        Text(
                            text = chapter.content,
                            style = ReadingTypography,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(80.dp))
                }
            } ?: Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }

    // Chapter picker dialog
    if (showChapterPicker) {
        AlertDialog(
            onDismissRequest = { showChapterPicker = false },
            title = { Text("Chọn chương") },
            text = {
                Column {
                    chapters.forEachIndexed { index, chapter ->
                        TextButton(
                            onClick = {
                                viewModel.loadChapter(index)
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
            },
            confirmButton = {
                TextButton(onClick = { showChapterPicker = false }) {
                    Text("Đóng")
                }
            }
        )
    }

    // Settings dialog
    if (showSettings) {
        ReaderSettingsDialog(
            onDismiss = { showSettings = false },
            onServerUrlChange = { url -> viewModel.updateTtsServerUrl(url) }
        )
    }
}

@Composable
fun BuildAnnotatedStringWithHighlight(
    sentences: List<com.ebookreader.tts.SentenceSpan>,
    currentIndex: Int,
    isPlaying: Boolean
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val builder = AnnotatedString.Builder()

    sentences.forEachIndexed { index, sentence ->
        val start = builder.length
        builder.append(sentence.text)
        val end = builder.length

        if (index == currentIndex && isPlaying) {
            builder.addStyle(
                SpanStyle(
                    background = highlightColor,
                    fontWeight = FontWeight.Bold
                ),
                start,
                end
            )
        }

        // Add space between sentences
        builder.append(" ")
    }

    BasicText(
        text = builder.toAnnotatedString(),
        style = ReadingTypography.copy(color = MaterialTheme.colorScheme.onSurface)
    )
}

@Composable
fun ReaderBottomBar(
    ttsState: TtsState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
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
            // Previous chapter
            IconButton(
                onClick = onPreviousChapter,
                enabled = hasPrevious
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Chương trước")
            }

            // Play/Pause
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

            // Stop
            IconButton(
                onClick = onStop,
                enabled = ttsState != TtsState.IDLE && ttsState != TtsState.STOPPED
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Dừng")
            }

            // Next chapter
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
    var serverUrl by remember { mutableStateOf("http://221.132.21.49:8080") }

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
