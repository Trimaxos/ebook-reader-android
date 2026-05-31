package com.ebookreader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val voices by viewModel.voices.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // TTS Server Section
            Text(
                text = "TTS Server",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = settings.ttsServerUrl,
                onValueChange = { viewModel.updateTtsServerUrl(it) },
                label = { Text("Server URL") },
                placeholder = { Text("http://221.132.21.49:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Dns, contentDescription = null)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            // Connection test
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.testConnection() },
                    enabled = connectionStatus !is ConnectionStatus.Testing
                ) {
                    if (connectionStatus is ConnectionStatus.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.NetworkCheck, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Kiểm tra kết nối")
                }

                when (val status = connectionStatus) {
                    is ConnectionStatus.Connected -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Đã kết nối",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is ConnectionStatus.Error -> {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }

            HorizontalDivider()

            // Voice Selection
            Text(
                text = "Giọng đọc",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (voices.isNotEmpty()) {
                var expandedVoice by remember { mutableStateOf(false) }
                val selectedVoice = voices.find { it.shortName == settings.ttsVoice }

                ExposedDropdownMenuBox(
                    expanded = expandedVoice,
                    onExpandedChange = { expandedVoice = it }
                ) {
                    OutlinedTextField(
                        value = selectedVoice?.friendlyName ?: settings.ttsVoice,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Giọng đọc") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedVoice) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedVoice,
                        onDismissRequest = { expandedVoice = false }
                    ) {
                        voices.forEach { voice ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(voice.friendlyName)
                                        Text(
                                            "${voice.locale} - ${voice.gender}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.updateTtsVoice(voice.shortName)
                                    expandedVoice = false
                                }
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = settings.ttsVoice,
                    onValueChange = { viewModel.updateTtsVoice(it) },
                    label = { Text("Giọng đọc (Voice name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("vi-VN-HoaiMyNeural") }
                )
            }

            // TTS Rate
            Text(
                text = "Tốc độ đọc: ${settings.ttsRate}",
                style = MaterialTheme.typography.bodyMedium
            )
            val rateOptions = listOf("-50%", "-25%", "+0%", "+25%", "+50%")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rateOptions.forEach { rate ->
                    FilterChip(
                        selected = settings.ttsRate == rate,
                        onClick = { viewModel.updateTtsRate(rate) },
                        label = { Text(rate) }
                    )
                }
            }

            HorizontalDivider()

            // Reading Settings
            Text(
                text = "Hiển thị",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Font size
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.TextFields, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cỡ chữ: ${settings.fontSize}")
                    Slider(
                        value = settings.fontSize.toFloat(),
                        onValueChange = { viewModel.updateFontSize(it.toInt()) },
                        valueRange = 12f..32f,
                        steps = 19
                    )
                }
            }

            // Line spacing
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.FormatLineSpacing, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Khoảng cách dòng: %.1f".format(settings.lineSpacing))
                    Slider(
                        value = settings.lineSpacing,
                        onValueChange = { viewModel.updateLineSpacing(it) },
                        valueRange = 1.0f..2.5f,
                        steps = 14
                    )
                }
            }

            // Reading theme
            Text(
                text = "Chủ đề đọc",
                style = MaterialTheme.typography.bodyMedium
            )
            val themeOptions = listOf(
                "light" to "Sáng",
                "sepia" to "Sepia",
                "dark" to "Tối"
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                themeOptions.forEach { (key, label) ->
                    FilterChip(
                        selected = settings.readingTheme == key,
                        onClick = { viewModel.updateReadingTheme(key) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // About section
            HorizontalDivider()
            Text(
                text = "Ứng dụng đọc sách",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Phiên bản 1.0.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
