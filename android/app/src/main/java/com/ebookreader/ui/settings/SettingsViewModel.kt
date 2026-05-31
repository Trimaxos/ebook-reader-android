package com.ebookreader.ui.settings

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ebookreader.tts.TtsClient
import com.ebookreader.tts.VoiceInfo
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Application.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val ttsServerUrl: String = "https://tts.ngtri.io.vn",
    val ttsApiKey: String = "dCUHsBmDQJws88KGk_t1tl-fNGAORdOYdpkqPPNKGPI",
    val ttsVoice: String = "vi-VN-HoaiMyNeural",
    val ttsRate: String = "+0%",
    val fontSize: Int = 18,
    val lineSpacing: Float = 1.5f,
    val readingTheme: String = "light"
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.dataStore
    private val ttsClient = TtsClient()

    companion object {
        private val KEY_TTS_SERVER_URL = stringPreferencesKey("tts_server_url")
        private val KEY_TTS_API_KEY = stringPreferencesKey("tts_api_key")
        private val KEY_TTS_VOICE = stringPreferencesKey("tts_voice")
        private val KEY_TTS_RATE = stringPreferencesKey("tts_rate")
        private val KEY_FONT_SIZE = intPreferencesKey("font_size")
        private val KEY_LINE_SPACING = floatPreferencesKey("line_spacing")
        private val KEY_READING_THEME = stringPreferencesKey("reading_theme")
    }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                _settings.value = AppSettings(
                    ttsServerUrl = prefs[KEY_TTS_SERVER_URL] ?: "https://tts.ngtri.io.vn",
                    ttsApiKey = prefs[KEY_TTS_API_KEY] ?: "dCUHsBmDQJws88KGk_t1tl-fNGAORdOYdpkqPPNKGPI",
                    ttsVoice = prefs[KEY_TTS_VOICE] ?: "vi-VN-HoaiMyNeural",
                    ttsRate = prefs[KEY_TTS_RATE] ?: "+0%",
                    fontSize = prefs[KEY_FONT_SIZE] ?: 18,
                    lineSpacing = prefs[KEY_LINE_SPACING] ?: 1.5f,
                    readingTheme = prefs[KEY_READING_THEME] ?: "light"
                )
            }
        }
    }

    fun updateTtsServerUrl(url: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_TTS_SERVER_URL] = url
            }
            ttsClient.updateServerUrl(url)
        }
    }

    fun updateTtsApiKey(key: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_TTS_API_KEY] = key
            }
            ttsClient.updateApiKey(key)
        }
    }

    fun updateTtsVoice(voice: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_TTS_VOICE] = voice
            }
        }
    }

    fun updateTtsRate(rate: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_TTS_RATE] = rate
            }
        }
    }

    fun updateFontSize(size: Int) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_FONT_SIZE] = size
            }
        }
    }

    fun updateLineSpacing(spacing: Float) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_LINE_SPACING] = spacing
            }
        }
    }

    fun updateReadingTheme(theme: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_READING_THEME] = theme
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.Testing
            val settings = _settings.value
            ttsClient.updateServerUrl(settings.ttsServerUrl)
            ttsClient.updateApiKey(settings.ttsApiKey)
            val result = ttsClient.healthCheck()
            _connectionStatus.value = if (result.isSuccess) {
                val json = result.getOrThrow()
                val authenticated = json.optBoolean("authenticated", false)
                if (!authenticated) {
                    ConnectionStatus.Error("API key không hợp lệ")
                } else {
                    val voicesResult = ttsClient.getVoices()
                    if (voicesResult.isSuccess) {
                        _voices.value = voicesResult.getOrThrow()
                    }
                    ConnectionStatus.Connected
                }
            } else {
                ConnectionStatus.Error(
                    result.exceptionOrNull()?.message ?: "Kết nối thất bại"
                )
            }
        }
    }

    fun refreshVoices() {
        viewModelScope.launch {
            val settings = _settings.value
            ttsClient.updateServerUrl(settings.ttsServerUrl)
            ttsClient.updateApiKey(settings.ttsApiKey)
            val result = ttsClient.getVoices()
            if (result.isSuccess) {
                _voices.value = result.getOrThrow()
            }
        }
    }
}

sealed class ConnectionStatus {
    object Unknown : ConnectionStatus()
    object Testing : ConnectionStatus()
    object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}
