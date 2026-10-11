package dev.remoteagent.app.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Live speech-to-text for the composer, on the device where it can be, in the
 * user's keyboard languages (up to three, the last one used first). Mirrors
 * apple/Shared/Dictation.swift; Android's recognizer detects the language itself.
 */
@Stable
class Dictation(private val context: Context, private val settings: AppSettings, private val scope: CoroutineScope) {
    enum class Phase { IDLE, STARTING, DOWNLOADING, LISTENING, FINISHING }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var error by mutableStateOf<String?>(null)

    private var recognizer: SpeechRecognizer? = null
    // Bumped by every start and by stop, so a start that was stopped midway gives up.
    private var attempt = 0
    private val main = Executor { context.mainExecutor.execute(it) }

    /**
     * Starts listening. onText gets the whole transcript so far each time it
     * changes. hints are words to expect, such as the project's name.
     */
    fun start(hints: List<String>, onText: (String) -> Unit) {
        if (phase != Phase.IDLE) return
        attempt += 1
        val attempt = attempt
        phase = Phase.STARTING
        scope.launch {
            val languages = languages()
            if (this@Dictation.attempt != attempt) return@launch
            val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
                fail("Speech recognition isn't available on this device.")
                return@launch
            }
            val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages.first())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (languages.size > 1) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(languages))
                }
                if (hints.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
                // Keeps listening through pauses, until stop() (or a long silence).
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
            }
            r.setRecognitionListener(Listener(attempt, onText))
            if (onDevice) {
                r.checkRecognitionSupport(intent, main, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (this@Dictation.attempt != attempt) return
                        val wanted = languages.first()
                        val installed = support.installedOnDeviceLanguages.any { it.equals(wanted, true) }
                        val available = support.supportedOnDeviceLanguages.any { it.equals(wanted, true) } ||
                            support.pendingOnDeviceLanguages.any { it.equals(wanted, true) }
                        if (installed || !available) {
                            listen(r, intent, attempt)
                            return
                        }
                        phase = Phase.DOWNLOADING
                        r.triggerModelDownload(intent, main, object : ModelDownloadListener {
                            override fun onProgress(completedPercent: Int) {}
                            override fun onSuccess() = listen(r, intent, attempt)
                            override fun onScheduled() = fail("The speech model for your language is downloading. Try again in a moment.")
                            override fun onError(error: Int) = fail("The speech model for your language isn't available on this device.")
                        })
                    }

                    override fun onError(error: Int) = listen(r, intent, attempt)
                })
            } else {
                listen(r, intent, attempt)
            }
        }
    }

    private fun listen(r: SpeechRecognizer, intent: Intent, attempt: Int) {
        if (this.attempt != attempt) return
        r.startListening(intent)
        phase = Phase.LISTENING
    }

    /** Stops listening. The transcript still gets its final words. */
    fun stop() {
        when (phase) {
            Phase.IDLE, Phase.FINISHING -> return
            Phase.STARTING, Phase.DOWNLOADING -> {
                attempt += 1
                release()
            }
            Phase.LISTENING -> {
                phase = Phase.FINISHING
                recognizer?.stopListening()
            }
        }
    }

    private fun fail(message: String) {
        error = message
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
        phase = Phase.IDLE
    }

    private inner class Listener(private val attempt: Int, private val onText: (String) -> Unit) : RecognitionListener {
        // Finished segments, then the one being spoken.
        private val segments = mutableListOf<String>()

        private fun show(partial: String?) {
            if (this@Dictation.attempt != attempt) return
            onText((segments + listOfNotNull(partial?.takeIf { it.isNotBlank() })).joinToString(" "))
        }

        private fun text(results: Bundle?) = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        override fun onPartialResults(partialResults: Bundle?) = show(text(partialResults))

        override fun onSegmentResults(segmentResults: Bundle) {
            text(segmentResults)?.takeIf { it.isNotBlank() }?.let { segments += it }
            show(null)
        }

        override fun onResults(results: Bundle?) {
            text(results)?.takeIf { it.isNotBlank() }?.let { segments += it }
            show(null)
            done()
        }

        override fun onEndOfSegmentedSession() = done()

        override fun onLanguageDetection(results: Bundle) {
            results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let { settings.put(AppSettings.Keys.dictationLanguage, it) }
        }

        override fun onError(error: Int) {
            if (this@Dictation.attempt != attempt) return
            // Silence or nothing understood isn't worth an alert.
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_CLIENT) {
                this@Dictation.error = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Remote Agent needs the microphone to dictate."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "The speech model for your language isn't available on this device."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        "Dictation needs a network connection on this device."
                    else -> "Dictation stopped (error $error)."
                }
            }
            done()
        }

        private fun done() {
            if (this@Dictation.attempt != attempt) return
            release()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * The keyboard's languages, one per language and the last one dictated
     * first; up to three. Falls back to the app's language.
     */
    private suspend fun languages(): List<String> {
        val imm = context.getSystemService(InputMethodManager::class.java)
        val tags = buildList {
            imm?.currentInputMethodSubtype?.languageTag?.let(::add)
            imm?.enabledInputMethodList?.forEach { ime ->
                imm.getEnabledInputMethodSubtypeList(ime, true).forEach { add(it.languageTag) }
            }
        }.filter { it.isNotBlank() }
        val byLanguage = LinkedHashMap<String, String>()
        for (tag in tags + Locale.getDefault().toLanguageTag()) {
            val language = Locale.forLanguageTag(tag).language
            if (language.isNotEmpty() && language !in byLanguage) byLanguage[language] = tag
        }
        val preferred = settings.get(AppSettings.Keys.dictationLanguage)?.let { Locale.forLanguageTag(it).language }
        val ordered = byLanguage.entries.sortedBy { if (it.key == preferred) 0 else 1 }.map { it.value }
        return ordered.take(3)
    }
}
