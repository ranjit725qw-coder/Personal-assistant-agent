package com.jarves.mh.ui

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.UUID

/** Preset voice styles for reading AI replies aloud. */
enum class VoiceStyle(val labelBn: String, val pitch: Float, val rate: Float) {
    NATURAL("স্বাভাবিক", 1.0f, 1.0f),
    FEMALE("মেয়ে কণ্ঠ", 1.4f, 1.0f),
    MALE("ছেলে কণ্ঠ", 0.7f, 0.95f),
    ROBOT("রোবট", 0.5f, 1.2f);

    fun next(): VoiceStyle = entries[(ordinal + 1) % entries.size]
}

/**
 * App-wide Text-To-Speech helper (custom feature). Call [speak] with any text;
 * markdown and code blocks are stripped so the voice reads clean sentences.
 *
 * Tip: install the Bengali voice in the phone's Text-To-Speech settings
 * (Settings > System > Languages > Text-to-speech) for better Bangla reading.
 */
object SpeechReader {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    var style by mutableStateOf(VoiceStyle.NATURAL)
    var isSpeaking by mutableStateOf(false)
        private set

    @Synchronized
    private fun ensureInit(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val engine = tts ?: return@TextToSpeech
                val picked = engine.setLanguage(Locale.getDefault())
                if (picked == TextToSpeech.LANG_MISSING_DATA || picked == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.US
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                    }
                })
            }
        }
    }

    fun speak(context: Context, rawText: String) {
        ensureInit(context)
        val engine = tts
        if (engine == null || !ready) {
            Toast.makeText(context, "ভয়েস ইঞ্জিন চালু হচ্ছে, একটু পরে চাপুন", Toast.LENGTH_SHORT).show()
            return
        }
        val clean = stripMarkdown(rawText)
        if (clean.isBlank()) {
            Toast.makeText(context, "পড়ার মতো লেখা নেই", Toast.LENGTH_SHORT).show()
            return
        }
        engine.setPitch(style.pitch)
        engine.setSpeechRate(style.rate)
        isSpeaking = true
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), "mh-" + UUID.randomUUID())
    }

    fun stop() {
        runCatching { tts?.stop() }
        isSpeaking = false
    }

    fun cycleStyle(context: Context) {
        style = style.next()
        Toast.makeText(context, "ভয়েস: ${style.labelBn}", Toast.LENGTH_SHORT).show()
    }

    /** Removes code blocks and markdown symbols so TTS reads clean sentences. */
    fun stripMarkdown(text: String): String {
        var out = text
        out = out.replace(Regex("```[\\s\\S]*?```"), " [code omitted] ")
        out = out.replace(Regex("`([^`]*)`"), "$1")
        out = out.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        out = out.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        out = out.replace(Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE), "")
        out = out.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        out = out.replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")
        out = out.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")
        out = out.replace(Regex("[*_~|]"), "")
        out = out.replace(Regex("\\s+"), " ").trim()
        return out.take(3900)
    }
}

/**
 * Speaker row shown under each AI message bubble.
 * Tap = read aloud / stop. Long-press = change voice style.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeakButton(textToSpeak: String) {
    val context = LocalContext.current
    val speakingNow = SpeechReader.isSpeaking
    val style = SpeechReader.style
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (speakingNow) Icons.Default.Stop else Icons.Default.VolumeUp,
            contentDescription = if (speakingNow) "Stop reading" else "Read aloud",
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        if (speakingNow) {
                            SpeechReader.stop()
                        } else {
                            SpeechReader.speak(context, textToSpeak)
                        }
                    },
                    onLongClick = { SpeechReader.cycleStyle(context) },
                )
                .padding(6.dp),
            tint = if (speakingNow) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = if (speakingNow) "পড়ছে… (থামাতে চাপুন)" else "${style.labelBn} • লং-প্রেসে ভয়েস বদলান",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
