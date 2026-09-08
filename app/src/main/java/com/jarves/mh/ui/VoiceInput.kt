package com.jarves.mh.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import com.jarves.mh.ui.theme.PocketOrange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Custom feature (not in the original repo): a microphone button for the chat
 * input box. Tap to speak in Bangla/English, tap again to finish. The
 * recognized text is appended to the chat box via [onResult].
 *
 * Requires the RECORD_AUDIO permission in AndroidManifest.xml.
 * Speech recognition needs internet (Google voice typing) on most phones.
 */
@Composable
fun VoiceInputButton(
    enabled: Boolean = true,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    // Idle/off state: black on light theme, muted gray on dark theme (black would vanish there).
    val idleMicTint = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Black
    var listening by remember { mutableStateOf(false) }
    val recognizerRef = remember { arrayOfNulls<SpeechRecognizer>(1) }

    fun cleanup() {
        runCatching { recognizerRef[0]?.cancel() }
        runCatching { recognizerRef[0]?.destroy() }
        recognizerRef[0] = null
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "Voice recognition not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        cleanup()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizerRef[0] = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                listening = false
                cleanup()
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    Toast.makeText(context, "কিছু শোনা যায়নি, আবার বলুন", Toast.LENGTH_SHORT).show()
                } else if (error != SpeechRecognizer.ERROR_CLIENT) {
                    Toast.makeText(context, "শুনতে পাইনি, আবার চেষ্টা করুন", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                cleanup()
                if (heard != null) {
                    onResult(heard.trim())
                } else {
                    Toast.makeText(context, "কিছু বোঝা যায়নি, আবার বলুন", Toast.LENGTH_SHORT).show()
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        listening = true
        runCatching { recognizer.startListening(intent) }.onFailure {
            listening = false
            cleanup()
            Toast.makeText(context, "মাইক চালু হয়নি, আবার চেষ্টা করুন", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopAndKeep() {
        // stopListening() keeps the audio captured so far and still triggers onResults.
        runCatching { recognizerRef[0]?.stopListening() }.onFailure {
            listening = false
            cleanup()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            Toast.makeText(context, "ভয়েস ইনপুটের জন্য মাইকের অনুমতি দিন", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose { cleanup() }
    }

    IconButton(
        onClick = {
            if (listening) {
                stopAndKeep()
                return@IconButton
            }
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                startListening()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = if (listening) "Stop listening" else "Voice input",
            modifier = Modifier.size(20.dp),
            tint = when {
                listening -> PocketOrange
                enabled -> idleMicTint
                else -> idleMicTint.copy(alpha = 0.4f)
            },
        )
    }
}
