package com.vcorr.claudewatch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/** One way of turning the wearer's speech into text. Callbacks arrive on the main thread. */
interface SpeechInput {

    interface Listener {
        fun onListening()
        fun onSpeechStarted()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onNothingHeard()

        /** [routeUnavailable] means this route can't work on this watch, so another should be tried. */
        fun onError(message: String, routeUnavailable: Boolean)
    }

    fun start(listener: Listener)

    /** Stops capturing but still delivers what was heard. */
    fun stop()

    /** Stops and delivers nothing. */
    fun cancel()

    fun destroy()

    companion object {
        fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, englishTag())

        /** The watch's own English (en-GB on the owner's watch), or US English if it isn't set to English. */
        fun englishTag(): String =
            Locale.getDefault().takeIf { it.language == "en" }?.toLanguageTag() ?: "en-US"

        fun errorName(code: Int) = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout (1)"
            SpeechRecognizer.ERROR_NETWORK -> "network error (2)"
            SpeechRecognizer.ERROR_AUDIO -> "audio error (3)"
            SpeechRecognizer.ERROR_SERVER -> "server error (4)"
            SpeechRecognizer.ERROR_CLIENT -> "client error (5)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "heard nothing (6)"
            SpeechRecognizer.ERROR_NO_MATCH -> "didn't understand (7)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy (8)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission (9)"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests (10)"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "service disconnected (11)"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported (12)"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable (13)"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "can't check support (14)"
            else -> "error $code"
        }
    }
}

/**
 * The in-app recogniser: live partial transcript, and the app controls the follow-up window.
 *
 * A watch can have several speech routes: the default service, an on-device recogniser (API 31+),
 * and each installed service (a Galaxy Watch has Google's and Samsung's). The default may refuse
 * other apps, and a service may lack an en-US model. So this tries each route in turn, first asking
 * for en-US and then, if the language is the problem, the watch's own language, and sticks with the
 * first that gets as far as listening. [log], when set, hears about every attempt.
 *
 * Every listen gets a fresh recogniser: on a Galaxy Watch a reused one fails its next start. Once a
 * route has listened, a later failure to start is retried once and then reported as an ordinary
 * error, rather than written off as a route that can't work.
 */
class InAppSpeechInput(private val context: Context) : SpeechInput {

    private sealed class Route(val label: String) {
        object Default : Route("default service")
        object OnDevice : Route("on-device")
        class Service(val component: ComponentName) : Route(component.packageName)
    }

    var log: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val routes: List<Route> by lazy { findRoutes(context) }
    private var routeIndex = 0
    private var askForEnglish = true
    private val english = SpeechInput.englishTag()

    // The route that has listened this session, and whether this start has had its one retry.
    private var provenRoute = -1
    private var retried = false
    private var recognizer: SpeechRecognizer? = null
    private var active: SpeechInput.Listener? = null
    private var watchdog: Runnable? = null

    // Each attempt gets a number, so late callbacks from an abandoned one are ignored.
    private var attempt = 0

    override fun start(listener: SpeechInput.Listener) {
        cancel()
        active = listener
        retried = false
        if (routes.isEmpty()) {
            active = null
            listener.onError("No speech service works in-app", routeUnavailable = true)
            return
        }
        startWith(listener)
    }

    private fun startWith(listener: SpeechInput.Listener) {
        val route = routes[routeIndex]
        recognizer?.destroy()
        recognizer = null
        val sr = try {
            create(route).also { recognizer = it }
        } catch (e: Exception) {
            tryNextRoute(listener, "couldn't be created (${e.javaClass.simpleName})")
            return
        }
        var ready = false
        val thisAttempt = ++attempt
        val language = if (askForEnglish) english else "watch language"
        report("${route.label} ($language): starting")

        // A route that neither starts nor fails counts as not working.
        val timeout = Runnable {
            if (active === listener && attempt == thisAttempt && !ready) {
                tryNextRoute(listener, "didn't start within ${START_TIMEOUT_MS / 1000} s")
            }
        }
        watchdog = timeout
        main.postDelayed(timeout, START_TIMEOUT_MS)

        sr.setRecognitionListener(object : RecognitionListener {
            private fun current() = active === listener && attempt == thisAttempt

            override fun onReadyForSpeech(params: Bundle?) {
                ready = true
                main.removeCallbacks(timeout)
                if (!current()) return
                provenRoute = routeIndex
                report("${route.label} ($language): listening")
                listener.onListening()
            }

            override fun onBeginningOfSpeech() {
                if (current()) listener.onSpeechStarted()
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (current() && !text.isNullOrBlank()) listener.onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                main.removeCallbacks(timeout)
                active = null
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) listener.onNothingHeard() else listener.onResult(text)
            }

            override fun onError(error: Int) {
                if (!current()) return
                main.removeCallbacks(timeout)
                if (!ready && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    val languageProblem = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    if (languageProblem && askForEnglish) {
                        // Same route again in the watch's own language.
                        report("${route.label} ($english): ${SpeechInput.errorName(error)}")
                        askForEnglish = false
                        startWith(listener)
                        return
                    }
                    val transient = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                    if (transient && !retried) {
                        // Often the previous listen hasn't let go yet: try once more, a moment later.
                        retried = true
                        report("${route.label} ($language): ${SpeechInput.errorName(error)}, retrying")
                        recognizer?.destroy()
                        recognizer = null
                        main.postDelayed({ if (current()) startWith(listener) }, RETRY_DELAY_MS)
                        return
                    }
                    // Failed before it ever listened, e.g. "no selected voice recognition service".
                    tryNextRoute(listener, SpeechInput.errorName(error))
                    return
                }
                active = null
                report("${route.label}: ${SpeechInput.errorName(error)}")
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> listener.onNothingHeard()
                    SpeechRecognizer.ERROR_AUDIO -> listener.onError("Microphone problem", routeUnavailable = false)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        listener.onError("Speech recognition needs a connection", routeUnavailable = false)
                    else -> listener.onError("Speech recognition failed ($error)", routeUnavailable = false)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (askForEnglish) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, english)
        sr.startListening(intent)
    }

    private fun create(route: Route): SpeechRecognizer = when (route) {
        Route.Default -> SpeechRecognizer.createSpeechRecognizer(context)
        Route.OnDevice -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            error("on-device recognition needs API 31")
        }
        is Route.Service -> SpeechRecognizer.createSpeechRecognizer(context, route.component)
    }

    private fun tryNextRoute(listener: SpeechInput.Listener, reason: String) {
        val route = routes[routeIndex]
        report("${route.label} (${if (askForEnglish) english else "watch language"}): $reason")
        recognizer?.destroy()
        recognizer = null
        if (routeIndex == provenRoute) {
            // This route has listened before, so it works here: a passing failure, not a dead end.
            active = null
            listener.onError("Speech recognition didn't start ($reason)", routeUnavailable = false)
            return
        }
        Log.d(TAG, "speech route ${route.label} unusable: $reason")
        askForEnglish = true
        routeIndex++
        if (routeIndex < routes.size) {
            startWith(listener)
        } else {
            routeIndex = 0
            active = null
            report("no route worked")
            listener.onError("No speech service works in-app", routeUnavailable = true)
        }
    }

    private fun report(line: String) {
        log?.invoke(line)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        active = null
        watchdog?.let(main::removeCallbacks)
        recognizer?.cancel()
    }

    override fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        private const val TAG = "ClaudeWatch"
        private const val START_TIMEOUT_MS = 5_000L
        private const val RETRY_DELAY_MS = 400L

        /** True if any in-app route might work, so it is worth trying before the system dialog. */
        fun isAvailable(context: Context): Boolean = findRoutes(context).isNotEmpty()

        private fun findRoutes(context: Context): List<Route> {
            val services = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { Route.Service(ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)) }
                .sortedBy { if (it.component.packageName.startsWith("com.google")) 0 else 1 }
            return buildList {
                if (SpeechRecognizer.isRecognitionAvailable(context)) add(Route.Default)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    add(Route.OnDevice)
                }
                addAll(services)
            }
        }
    }
}

/**
 * The system speech dialog, the route Google documents for Wear OS. The activity forwards
 * [onActivityResult] results through [handleResult].
 */
class DialogSpeechInput(private val activity: Activity, private val requestCode: Int) : SpeechInput {

    private var active: SpeechInput.Listener? = null

    val isOpen: Boolean get() = active != null

    override fun start(listener: SpeechInput.Listener) {
        active = listener
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(SpeechInput.recognizeIntent(), requestCode)
            listener.onListening()
        } catch (e: ActivityNotFoundException) {
            active = null
            listener.onError("No speech recogniser on this watch", routeUnavailable = true)
        }
    }

    /** Returns true if the result was this dialog's. */
    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != this.requestCode) return false
        val listener = active ?: return true
        active = null
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) listener.onResult(text) else listener.onNothingHeard()
        return true
    }

    override fun stop() {}

    override fun cancel() {
        if (active != null) activity.finishActivity(requestCode)
        active = null
    }

    override fun destroy() = cancel()
}
