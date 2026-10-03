package com.vcorr.claudewatch

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vcorr.claudewatch.core.Conversation
import com.vcorr.claudewatch.core.SpokenText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.coroutines.resume

class MainActivity : Activity() {

    private enum class State { IDLE, LISTENING, FOLLOW_UP, THINKING, SPEAKING, TYPING, SETUP }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var layoutVoice: LinearLayout
    private lateinit var ivClawd: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvLead: TextView
    private lateinit var tvMain: TextView
    private lateinit var micArea: View
    private lateinit var ringProgress: RingView
    private lateinit var btnTalk: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var tvCaption: TextView
    private lateinit var scrollReply: ScrollView
    private lateinit var tvReply: TextView
    private lateinit var scrim: View
    private lateinit var glow: View
    private lateinit var ivClawdSmall: ImageView
    private lateinit var tvHint: TextView
    private lateinit var btnCancel: Button
    private lateinit var rowActions: LinearLayout
    private lateinit var btnTalkSmall: ImageButton
    private lateinit var layoutTyping: LinearLayout
    private lateinit var etPrompt: EditText
    private lateinit var layoutSetup: ScrollView
    private lateinit var tvSetup: TextView

    private var state = State.IDLE

    // What the idle screen says: a plain note ("New chat"), or a problem with a retry button.
    private var idleMessage: String? = null
    private var idleProblem = false

    // The question being answered, and the last exchange that got a reply.
    private var pendingQuestion = ""
    private var shownQuestion = ""
    private var shownReply: String? = null

    // What the watch is doing for Claude ("Checking the weather"), shown while thinking and while
    // a tool runs after Claude has said it will look something up.
    private var toolLabel: String? = null

    // Set while the in-app follow-up window is counting down, so its ring is shown.
    private var followUpTimed = false

    private lateinit var clawdBob: ObjectAnimator
    private lateinit var overlay: Overlay
    private var followUpCountdown: ValueAnimator? = null

    // Bumped by every new turn or interruption; callbacks from older turns are ignored.
    private var turnToken = 0

    private val conversation = Conversation()
    private lateinit var watchTools: WatchTools
    private lateinit var speaker: Speaker
    private var inAppInput: InAppSpeechInput? = null
    private lateinit var dialogInput: DialogSpeechInput
    // Set when no in-app speech service works; for this session only, so the next launch tries again.
    private var useDialog = false
    // Which speech routes exist doesn't change while the app runs, so look once.
    private val inAppAvailable by lazy { InAppSpeechInput.isAvailable(this) }
    private var requestJob: Job? = null
    private var followUpJob: Job? = null
    private var closeJob: Job? = null
    // The reply being streamed, if any, so stopping it part-way can keep what was heard.
    private var liveReply: LiveReply? = null
    private var foreground = false
    private var listenOnLaunch = false
    // Set when the app goes to the background on its own account, so coming back (a double press of
    // the Home key, say) listens again, as a fresh launch does. Not set for our own screens.
    private var listenOnReturn = false
    private var leavingForOwnScreen = false
    // Until then, a stop is another app's screen a tool opened (the Clock's), not the wearer leaving.
    private var leavingForOtherAppUntil = 0L
    // Set for a listen the wearer didn't ask for in so many words (a launch or a return), so
    // silence then closes the app instead of leaving it open.
    private var closeIfSilent = false
    // Saving and clearing the chat run one at a time, so a clear can't be overtaken by a save.
    private val storeLock = Mutex()
    private var listenAfterPermission = false
    private var toolPermissionsAsked = false
    // Whether the wearer has been told, this run, that the watch can't do Finnish.
    private var toldAboutEnglish = false

    private lateinit var keySetup: KeySetupFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutVoice = findViewById(R.id.layout_voice)
        ivClawd = findViewById(R.id.iv_clawd)
        tvStatus = findViewById(R.id.tv_status)
        tvLead = findViewById(R.id.tv_lead)
        tvMain = findViewById(R.id.tv_main)
        micArea = findViewById(R.id.mic_area)
        ringProgress = findViewById(R.id.ring_progress)
        btnTalk = findViewById(R.id.btn_talk)
        tvTitle = findViewById(R.id.tv_title)
        tvCaption = findViewById(R.id.tv_caption)
        scrollReply = findViewById(R.id.scroll_reply)
        tvReply = findViewById(R.id.tv_reply)
        scrim = findViewById(R.id.scrim)
        glow = findViewById(R.id.glow)
        ivClawdSmall = findViewById(R.id.iv_clawd_small)
        tvHint = findViewById(R.id.tv_hint)
        btnCancel = findViewById(R.id.btn_cancel)
        rowActions = findViewById(R.id.row_actions)
        btnTalkSmall = findViewById(R.id.btn_talk_small)
        layoutTyping = findViewById(R.id.layout_typing)
        etPrompt = findViewById(R.id.et_prompt)
        layoutSetup = findViewById(R.id.layout_setup)
        tvSetup = findViewById(R.id.tv_setup)

        // A fresh start asks the recogniser for Finnish again (the speaker decides its half afresh).
        Language.finnishHeard = true
        speaker = Speaker(this)
        watchTools = WatchTools(this).apply {
            speaker = this@MainActivity.speaker
            // The Clock app may show its screen for a timer; coming back from it mustn't start
            // listening. If it shows nothing, the flag lapses rather than catching a later exit.
            beforeLeaving = { leavingForOtherAppUntil = SystemClock.uptimeMillis() + LEAVING_LAPSE_MS }
        }
        StepsService.register(this)
        dialogInput = DialogSpeechInput(this, REQ_DIALOG)

        val density = resources.displayMetrics.density
        clawdBob = ObjectAnimator.ofFloat(ivClawd, View.TRANSLATION_Y, 0f, -4f * density).apply {
            duration = 420
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        overlay = Overlay(scrim, glow, layoutVoice, getColor(R.color.accent))
        keySetup = KeySetupFlow(this, tvSetup) {
            goIdle("Key saved")
            Toast.makeText(this, "Key saved", Toast.LENGTH_SHORT).show()
        }

        btnTalk.setOnClickListener { onTalkTapped() }
        btnTalkSmall.setOnClickListener { onTalkTapped() }
        btnCancel.setOnClickListener { interrupt(null) }
        // A tap anywhere: stops Claude talking or the follow-up window, sends what was heard while
        // listening, and silences a spoken error.
        val stopAnywhere = View.OnClickListener {
            when (state) {
                State.SPEAKING, State.FOLLOW_UP -> interrupt(null)
                State.LISTENING -> currentInput().stop() // done talking: send it
                State.IDLE -> speaker.stop() // a spoken error
                else -> Unit
            }
        }
        layoutVoice.setOnClickListener(stopAnywhere)
        tvReply.setOnClickListener(stopAnywhere)
        // A tap while listening sends what was heard; a long press drops it instead.
        layoutVoice.setOnLongClickListener {
            val listening = state == State.LISTENING
            if (listening) interrupt(null)
            listening
        }
        // A soft shadow keeps text legible over a bright watch face.
        val shadow = 4f * density
        listOf(tvStatus, tvLead, tvMain, tvTitle, tvCaption, tvReply, tvHint).forEach {
            it.setShadowLayer(shadow, 0f, 0f, 0xCC000000.toInt())
        }
        // The voice diagnostics hide behind a long press on either microphone button.
        val openProbe = View.OnLongClickListener {
            interrupt(null)
            leavingForOwnScreen = true
            startActivity(Intent(this, ProbeActivity::class.java))
            true
        }
        btnTalk.setOnLongClickListener(openProbe)
        btnTalkSmall.setOnLongClickListener(openProbe)
        findViewById<Button>(R.id.btn_type).setOnClickListener { showTyping() }
        findViewById<Button>(R.id.btn_new).setOnClickListener { newChat() }
        findViewById<Button>(R.id.btn_send).setOnClickListener { submitTyped() }
        etPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false
        }

        // Launching the app goes straight into listening, like a voice assistant.
        listenOnLaunch = savedInstanceState == null
        render()

        // Carry on the last chat if it was recent; the reply view shows where it stopped.
        scope.launch {
            val saved = ConversationStore.load(this@MainActivity)
            if (saved.isEmpty() || conversation.all.isNotEmpty()) return@launch
            conversation.restore(saved)
            val turns = conversation.all
            if (turns.size >= 2) {
                shownQuestion = turns[turns.lastIndex - 1].text
                shownReply = turns.last().text
                tvReply.text = turns.last().text
                if (state == State.IDLE) render()
            }
        }
    }

    // Any touch or turn of the bezel means the wearer is reading: don't close under them.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        cancelClose()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        cancelClose()
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * Opened again while already running (the tile, a Home-key double press, the launcher): the
     * app is single-task, so this same instance gets the request. Whatever it was doing stops, and
     * it listens afresh, as a launch does.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (dialogInput.isOpen || state == State.SETUP) return
        interrupt(null)
        listenOnLaunch = true
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onResume() {
        super.onResume()
        // A permission prompt may only pause the app, never stopping it; either way it's over now.
        leavingForOwnScreen = false
        val returning = listenOnReturn
        listenOnReturn = false
        if (dialogInput.isOpen) return
        scope.launch {
            // Also restarts setup after a pause, e.g. when it was opened because the key was rejected.
            if (state == State.SETUP || ApiKeyStore.read(this@MainActivity) == null) {
                showSetup(keySetup.notice)
            } else if (listenOnLaunch) {
                listenOnLaunch = false
                startListening(followUp = false, unprompted = true)
            } else if (returning && state == State.IDLE) {
                startListening(followUp = false, unprompted = true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        keySetup.stop()
    }

    override fun onStop() {
        super.onStop()
        foreground = false
        cancelClose()
        // The system speech dialog stops this activity while it is open; that is expected.
        if (dialogInput.isOpen) return
        // Back from our own screens (diagnostics, permission prompts) stays as it was; back from
        // anywhere else listens, as a fresh launch would.
        // The screen going off (timeout, wrist down) also stops the app on Wear OS; waking it to reread
        // a reply must not open the microphone, so only a stop with the screen on counts as leaving.
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val forOtherApp = SystemClock.uptimeMillis() < leavingForOtherAppUntil
        listenOnReturn = screenOn && !leavingForOwnScreen && !forOtherApp && !listenAfterPermission
        leavingForOwnScreen = false
        leavingForOtherAppUntil = 0L
        // The microphone may only be used while the app is visible; a reply may finish speaking.
        if (state == State.LISTENING || state == State.FOLLOW_UP) interrupt(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        keySetup.stop()
        clawdBob.cancel()
        overlay.release()
        followUpCountdown?.cancel()
        inAppInput?.destroy()
        dialogInput.destroy()
        speaker.shutdown()
        scope.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        dialogInput.handleResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC || !listenAfterPermission) return
        listenAfterPermission = false
        toolPermissionsAsked = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            permissions.forEach { putBoolean("asked:$it", true) }
        }.apply()
        // The step feed can only start once its permission is granted.
        StepsService.register(this)
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startListening(followUp = false)
        } else {
            goIdle("Microphone permission is needed to talk", problem = true, spoken = Language.say("I need the microphone permission to hear you.", "Tarvitsen mikrofonin luvan, jotta kuulen sinua."))
        }
    }

    // ── The voice loop ──────────────────────────────────────

    private fun onTalkTapped() {
        when (state) {
            State.IDLE -> startListening(followUp = false)
            State.LISTENING -> currentInput().stop()
            State.FOLLOW_UP, State.THINKING, State.SPEAKING -> interrupt(null)
            State.TYPING, State.SETUP -> Unit
        }
    }

    private fun toolPermissionsToAsk(): Array<String> = WatchTools.permissions()
        .filter { !hasPermission(it) && !alreadyAsked(it) }
        .toTypedArray()

    // Each permission is asked for once; if refused, the tool tells Claude how the wearer can allow it.
    private fun alreadyAsked(permission: String): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("asked:$permission", false)

    private fun currentInput(): SpeechInput =
        if (!useDialog && inAppAvailable) {
            inAppInput ?: InAppSpeechInput(this).also { inAppInput = it }
        } else {
            dialogInput
        }

    private fun startListening(followUp: Boolean, unprompted: Boolean = false) {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            listenAfterPermission = true
            leavingForOwnScreen = true
            // The watch tools' permissions are asked for at the same time, once.
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO) + toolPermissionsToAsk(), REQ_MIC)
            return
        }
        val toolPermissions = if (toolPermissionsAsked) emptyArray() else toolPermissionsToAsk()
        if (toolPermissions.isNotEmpty()) {
            // The microphone is already allowed (an earlier version, or the diagnostics asked for
            // it) but some watch tools' permissions haven't been asked for: ask once, then listen.
            toolPermissionsAsked = true
            listenAfterPermission = true
            leavingForOwnScreen = true
            requestPermissions(toolPermissions, REQ_MIC)
            return
        }
        cancelClose()
        closeIfSilent = unprompted
        // Never listen over our own voice, such as a spoken error.
        speaker.stop()
        val token = ++turnToken
        val input = currentInput()
        state = if (followUp) State.FOLLOW_UP else State.LISTENING
        tvMain.text = ""
        // In-app follow-ups close after FOLLOW_UP_WINDOW_MS of silence; the system dialog has its own timeout.
        followUpTimed = followUp && input !== dialogInput
        keepScreenOn(true)
        render()
        buzz(VibrationEffect.EFFECT_TICK)

        var heardSpeech = false
        input.start(object : SpeechInput.Listener {
            private fun current() = token == turnToken

            override fun onListening() {
                if (current()) render()
            }

            override fun onSpeechStarted() {
                if (!current()) return
                heardSpeech = true
                followUpJob?.cancel()
                state = State.LISTENING
                render()
            }

            override fun onPartial(text: String) {
                if (!current()) return
                heardSpeech = true
                followUpJob?.cancel()
                state = State.LISTENING
                render()
                // The newest words matter most, so a long question shows its tail.
                tvMain.text = if (text.length > PARTIAL_CHARS) "…${text.takeLast(PARTIAL_CHARS)}…" else "$text…"
            }

            override fun onResult(text: String) {
                if (!current()) return
                followUpJob?.cancel()
                buzz(VibrationEffect.EFFECT_CLICK)
                ask(text)
            }

            override fun onNothingHeard() {
                if (!current()) return
                followUpJob?.cancel()
                if (followUp) {
                    endConversation()
                } else {
                    // A buzz, not a voice. Silence after a launch is often a launch by mistake, so
                    // then the app also closes itself unless touched.
                    goIdle("Didn't catch that", problem = true)
                    if (closeIfSilent) scheduleClose()
                }
            }

            override fun onError(message: String, routeUnavailable: Boolean) {
                if (!current()) return
                followUpJob?.cancel()
                if (routeUnavailable && input !== dialogInput) {
                    // No in-app speech service works here: use the system dialog for this session.
                    useDialog = true
                    startListening(followUp, closeIfSilent)
                    return
                }
                goIdle(message, problem = true, spoken = Language.say("Sorry, I couldn't listen just then.", "Anteeksi, en pystynyt kuuntelemaan juuri nyt."))
            }
        })

        if (followUpTimed) {
            startFollowUpCountdown()
            followUpJob = scope.launch {
                delay(FOLLOW_UP_WINDOW_MS)
                if (token == turnToken && !heardSpeech) {
                    input.cancel()
                    endConversation()
                }
            }
        }
    }

    private fun ask(text: String) {
        val token = turnToken
        cancelClose()
        liveReply = null
        toolLabel = null
        conversation.addUser(text)
        state = State.THINKING
        pendingQuestion = text
        render()

        requestJob = scope.launch {
            val key = ApiKeyStore.read(this@MainActivity)
            if (key == null) {
                showSetup(null)
                return@launch
            }
            // Whether this reply can be Finnish depends on the voice, which may still be checking
            // its engines just after launch: wait for it (briefly) before choosing.
            withTimeoutOrNull(VOICE_DECISION_MS) {
                suspendCancellableCoroutine<Unit> { cont -> speaker.whenDecided { if (cont.isActive) cont.resume(Unit) } }
            }
            if (token != turnToken) return@launch
            val finnish = Language.finnish
            val note = if (!finnish && !toldAboutEnglish) {
                toldAboutEnglish = true
                ENGLISH_NOTE.format(if (!Language.finnishSpoken) "no Finnish voice is installed" else "its speech recogniser has no Finnish")
            } else {
                null
            }
            val live = LiveReply(token, text)
            liveReply = live
            try {
                val reply = ClaudeApi.reply(
                    conversation.forRequest(),
                    key,
                    watchTools,
                    finnish = finnish,
                    note = note,
                    onProgress = { label ->
                        scope.launch {
                            if (token != turnToken) return@launch
                            toolLabel = label
                            render()
                        }
                    },
                    // Through the same queue the reply itself returns on, so no words arrive after it.
                    onText = { delta -> scope.launch { live.add(delta) } },
                )
                if (token != turnToken) return@launch
                live.complete = true
                liveReply = null
                toolLabel = null
                conversation.addAssistant(reply.text)
                saveChat()
                shownQuestion = text
                shownReply = reply.text
                tvReply.text = reply.display
                live.finish(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidApiKeyException) {
                liveReply = null
                speaker.stop()
                conversation.dropUnanswered()
                showSetup("The API key was rejected.")
            } catch (e: Exception) {
                if (token != turnToken) return@launch
                // Stop anything half-said, even when the error can't be spoken (screen off).
                liveReply = null
                toolLabel = null
                speaker.stop()
                conversation.dropUnanswered()
                if (e is IOException) {
                    goIdle("No connection to Claude", problem = true, spoken = Language.say("I can't reach Claude right now. Check the watch's connection.", "En saa yhteyttä Claudeen juuri nyt. Tarkista kellon yhteys."))
                } else {
                    goIdle(
                        "Claude had a problem: ${e.message?.take(80) ?: "unknown error"}",
                        problem = true,
                        spoken = Language.say("Claude had a problem. Try again in a moment.", "Claudella oli ongelma. Yritä hetken päästä uudelleen."),
                    )
                }
            }
        }
    }

    /** Speaks a reply that is already complete. */
    private fun speak(reply: String, token: Int) {
        state = State.SPEAKING
        scrollReply.scrollTo(0, 0)
        render()
        buzz(VibrationEffect.EFFECT_DOUBLE_CLICK)
        speaker.speak(SpokenText.clean(reply)) { onSpoken(token) }
    }

    /** The reply has been said: listen for a follow-up, if the wearer is still here. */
    private fun onSpoken(token: Int) {
        if (token != turnToken) return
        if (!foreground) {
            goIdle(null)
            return
        }
        // A short pause, so the microphone doesn't catch the tail of the reply.
        scope.launch {
            delay(MIC_DELAY_MS)
            if (token == turnToken && foreground) startListening(followUp = true)
        }
    }

    /**
     * A reply arriving as a stream: each sentence is spoken as soon as it is complete, and the text
     * grows on screen, so the wearer hears the start of the answer while the rest is on its way.
     * Call on the main thread.
     */
    private inner class LiveReply(private val token: Int, private val question: String) {
        private val text = StringBuilder()
        private var spokenUpTo = 0
        private var started = false

        /** The whole reply has arrived and been recorded. */
        var complete = false

        /**
         * Stopped part-way: keep what was said as the answer, so the question isn't lost and a
         * follow-up ("and tomorrow?") still has its context. Nothing is kept if nothing was said.
         */
        fun keepHeard() {
            if (complete || !started || token != turnToken) return
            complete = true
            val heard = text.substring(0, spokenUpTo).trim()
            if (heard.isEmpty()) return
            conversation.addAssistant(heard)
            saveChat()
            shownReply = heard
            tvReply.text = heard
        }

        fun add(delta: String) {
            if (token != turnToken || complete) return
            text.append(delta)
            val complete = SpokenText.completeLength(text.toString())
            if (complete > spokenUpTo) {
                say(text.substring(spokenUpTo, complete), atLineStart = spokenUpTo == 0)
                spokenUpTo = complete
            }
            if (started) tvReply.text = text.toString().trimStart()
        }

        /** The whole reply is in: say what is left, or the reply itself if nothing was said yet. */
        fun finish(reply: Reply) {
            if (reply.refused || !started) {
                speak(reply.text, token)
                return
            }
            // The last sentence has nothing after it, so it was never counted as complete.
            if (!reply.truncated) say(text.substring(spokenUpTo), atLineStart = spokenUpTo == 0)
            speaker.end()
        }

        // A chunk after the first starts mid-line, so a number at its start is not a list marker.
        private fun say(chunk: String, atLineStart: Boolean) {
            val sentences = SpokenText.spokenSentences(chunk, atLineStart)
            if (sentences.isEmpty()) return
            if (!started) begin()
            sentences.forEach(speaker::add)
        }

        private fun begin() {
            started = true
            shownQuestion = question
            state = State.SPEAKING
            tvReply.text = text.toString().trimStart()
            scrollReply.scrollTo(0, 0)
            render()
            buzz(VibrationEffect.EFFECT_DOUBLE_CLICK)
            speaker.begin { onSpoken(token) }
        }
    }

    /** Stops whatever is happening: listening, waiting for Claude, or speaking. */
    private fun interrupt(message: String?) {
        liveReply?.keepHeard()
        liveReply = null
        turnToken++
        requestJob?.cancel()
        followUpJob?.cancel()
        inAppInput?.cancel()
        dialogInput.cancel()
        speaker.stop()
        conversation.dropUnanswered()
        goIdle(message)
    }

    /**
     * Shows the idle face. A problem also buzzes and, if [spoken] is given and the app is in front,
     * is said aloud, since the wearer may not be looking.
     */
    private fun goIdle(message: String?, problem: Boolean = false, spoken: String? = null) {
        state = State.IDLE
        toolLabel = null
        idleMessage = message
        idleProblem = problem && message != null
        keepScreenOn(false)
        render()
        if (idleProblem) {
            buzz(VibrationEffect.EFFECT_HEAVY_CLICK)
            if (spoken != null && foreground) speaker.speak(spoken) {}
        }
    }

    /**
     * The follow-up window closed in silence: the conversation is over for now. The reply stays on
     * screen for a moment, then the app gets out of the way, as Gemini does. A touch keeps it open.
     */
    private fun endConversation() {
        goIdle(null)
        scheduleClose()
    }

    private fun scheduleClose() {
        closeJob?.cancel()
        closeJob = scope.launch {
            delay(AUTO_CLOSE_MS)
            if (state == State.IDLE) finish()
        }
    }

    /** Saves the chat in the background, after the reply is on its way, one write at a time. */
    private fun saveChat() {
        val snapshot = conversation.all
        scope.launch { storeLock.withLock { ConversationStore.save(this@MainActivity, snapshot) } }
    }

    private fun cancelClose() {
        closeJob?.cancel()
        closeJob = null
    }

    private fun newChat() {
        interrupt(null)
        conversation.clear()
        scope.launch { storeLock.withLock { ConversationStore.clear(this@MainActivity) } }
        shownQuestion = ""
        shownReply = null
        tvReply.text = ""
        goIdle("New chat")
    }

    // ── Typing ──────────────────────────────────────────────

    private fun showTyping() {
        interrupt(null)
        cancelClose()
        state = State.TYPING
        render()
        etPrompt.requestFocus()
    }

    private fun submitTyped() {
        val text = etPrompt.text.toString().trim()
        if (text.isEmpty()) return
        etPrompt.text.clear()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etPrompt.windowToken, 0)
        turnToken++
        keepScreenOn(true)
        ask(text)
    }

    // ── Screen ──────────────────────────────────────────────

    /** Shows the face for the current state, following the "ClaudeWatch voice states" design. */
    private fun render() {
        layoutVoice.visibility = if (state == State.TYPING || state == State.SETUP) View.GONE else View.VISIBLE
        layoutTyping.visibility = if (state == State.TYPING) View.VISIBLE else View.GONE
        layoutSetup.visibility = if (state == State.SETUP) View.VISIBLE else View.GONE

        val reply = shownReply
        val problem = state == State.IDLE && idleProblem
        // After an answer, idle keeps the reply on screen to read and scroll, with a small talk button.
        val readingReply = state == State.IDLE && !problem && idleMessage == null && reply != null

        ivClawd.show((state == State.IDLE && !problem && !readingReply) || state == State.THINKING || state == State.LISTENING)
        tvStatus.show(state == State.LISTENING || state == State.THINKING || (state == State.SPEAKING && toolLabel != null))
        tvStatus.text = when (state) {
            State.LISTENING -> "Listening"
            else -> toolLabel ?: "Thinking"
        }
        // The accent is hard to read against a bright watch face next to the glow; listening is
        // the face most often seen at a glance, so its label is light.
        tvStatus.setTextColor(getColor(if (state == State.LISTENING) R.color.soft else R.color.accent))

        tvLead.show(state == State.SPEAKING || state == State.FOLLOW_UP || readingReply)
        if (state == State.FOLLOW_UP && reply != null) {
            tvLead.maxLines = 2
            tvLead.text = SpokenText.spokenSentences(reply).lastOrNull()?.let { "…$it" }.orEmpty()
        } else {
            tvLead.maxLines = 1
            tvLead.text = shownQuestion
        }

        tvMain.show(state == State.LISTENING || state == State.THINKING)
        if (state == State.THINKING) tvMain.text = pendingQuestion
        // Heard words are the point while listening; the question is a reminder while thinking.
        if (state == State.THINKING) {
            tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tvMain.setTextColor(getColor(R.color.soft))
            tvMain.setTypeface(null, Typeface.NORMAL)
        } else {
            tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            tvMain.setTextColor(getColor(R.color.ink))
            tvMain.setTypeface(null, Typeface.BOLD)
        }

        // Listening has no button in the overlay: Clawd and the words heard, with the glow alive
        // behind them; a tap anywhere sends.
        micArea.show(state == State.IDLE && !readingReply || state == State.FOLLOW_UP)
        ringProgress.show(state == State.FOLLOW_UP && followUpTimed)
        btnTalk.setBackgroundResource(if (problem) R.drawable.bg_mic_outline else R.drawable.bg_mic_filled)
        btnTalk.imageTintList = getColorStateList(if (problem) R.color.accent else R.color.ink_on_accent)
        btnTalk.contentDescription = when {
            state == State.FOLLOW_UP -> "Stop listening"
            problem -> "Try again"
            else -> getString(R.string.talk_to_claude)
        }

        tvTitle.show(state == State.IDLE && !readingReply || state == State.FOLLOW_UP)
        tvTitle.text = if (state == State.FOLLOW_UP) "Anything else?" else idleMessage ?: "Tap to talk"
        tvCaption.show(problem)

        scrollReply.show(state == State.SPEAKING || readingReply)
        ivClawdSmall.show(state == State.SPEAKING)
        tvHint.show(state == State.SPEAKING || state == State.LISTENING)
        tvHint.text = if (state == State.LISTENING) "Tap to send, hold to cancel" else "Tap anywhere to stop"
        layoutVoice.contentDescription = when (state) {
            State.LISTENING -> "Listening. Double-tap to send."
            State.SPEAKING -> "Claude is speaking. Double-tap to stop."
            State.FOLLOW_UP -> "Listening for a follow-up. Double-tap to stop."
            else -> null
        }
        btnCancel.show(state == State.THINKING)
        rowActions.show(state == State.IDLE)
        btnTalkSmall.show(readingReply)

        clawdBob.runWhile(state == State.THINKING)
        renderOverlay(readingReply || problem)
        if (state != State.FOLLOW_UP) followUpCountdown?.cancel()
        // Lets the watch's bezel or crown scroll the reply.
        if (scrollReply.visibility == View.VISIBLE) scrollReply.requestFocus()
    }

    /**
     * The overlay around the faces: how dark the veil over the watch face is, where the glow sits,
     * and whether the content gathers at the bottom near the glow (listening, thinking) or centres.
     */
    private fun renderOverlay(reading: Boolean) {
        val veil = when (state) {
            State.LISTENING -> 0.72f
            State.THINKING -> 0.74f
            State.FOLLOW_UP -> 0.75f
            State.SPEAKING -> 0.85f
            State.IDLE -> if (reading) 0.85f else 0.75f
            State.TYPING, State.SETUP -> 0.94f
        }
        val glowSink = when (state) {
            State.LISTENING -> 110
            State.THINKING -> 125
            State.SPEAKING, State.FOLLOW_UP -> 150
            else -> 160
        }
        overlay.show(
            veilAlpha = veil,
            glowVisible = state != State.TYPING && state != State.SETUP,
            glowSinkDp = glowSink,
            contentAtBottom = state == State.LISTENING || state == State.THINKING,
            breathe = state == State.LISTENING,
        )
    }

    private fun startFollowUpCountdown() {
        followUpCountdown?.cancel()
        ringProgress.progress = 1f
        followUpCountdown = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = FOLLOW_UP_WINDOW_MS
            interpolator = LinearInterpolator()
            addUpdateListener { ringProgress.progress = it.animatedValue as Float }
            start()
        }
    }

    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Haptics tell the wearer where things stand without looking: a tick when listening starts, a
    // click when their words are in, a double click as the answer begins, a heavy click for a problem.
    private fun buzz(effect: Int) {
        getSystemService(Vibrator::class.java)
            ?.takeIf { it.hasVibrator() }
            ?.vibrate(VibrationEffect.createPredefined(effect))
    }

    // ── Key setup over the local Wi-Fi ──────────────────────

    private fun showSetup(notice: String?) {
        interrupt(null)
        cancelClose()
        state = State.SETUP
        render()
        keepScreenOn(true)
        keySetup.start(notice)
    }

    private companion object {
        const val REQ_MIC = 1
        const val REQ_DIALOG = 2
        const val FOLLOW_UP_WINDOW_MS = 3_500L
        const val MIC_DELAY_MS = 200L
        const val AUTO_CLOSE_MS = 8_000L
        // A cold start of the Clock app on a watch can take several seconds.
        const val LEAVING_LAPSE_MS = 8_000L
        const val VOICE_DECISION_MS = 4_000L
        // Said by the English voice, so in English only.
        const val ENGLISH_NOTE = "This watch can't do Finnish just now (%s). Begin your reply with one short " +
            "sentence telling the wearer so and that you'll speak English, then answer in English."
        const val PARTIAL_CHARS = 70
        const val PREFS = "claudewatch"
    }
}
