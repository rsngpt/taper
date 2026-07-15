package dev.taper.demo

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import dev.taper.classify.FailureCategory
import dev.taper.queue.CoalescedBatch
import dev.taper.queue.ConnectivityDrainTrigger
import dev.taper.queue.DrainReport
import dev.taper.queue.SyncQueue
import dev.taper.queue.Syncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Demo wiring of all three Taper components against a LIVE local model server:
 *
 *  1. [OllamaAgent] parses every real response with TaperParser (fields of interest).
 *  2. Every failure is shown WITH the classifier's verdict — TRANSIENT turns are
 *     enqueued to the durable [SyncQueue], SEMANTIC turns halt visibly.
 *  3. [ConnectivityDrainTrigger] replays the queue when connectivity returns
 *     (toggle airplane mode to watch it happen); "Drain now" does it manually.
 *
 * Server: run `ollama serve` + `ollama pull qwen2.5:3b` on the host machine.
 * The emulator reaches it at 10.0.2.2; a physical device needs
 * `adb reverse tcp:11434 tcp:11434` and BASE_URL of http://127.0.0.1:11434.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Drains run here, NOT on the main scope: the syncer does blocking network I/O,
     * and Android kills main-thread networking with NetworkOnMainThreadException.
     * Found the hard way — the first live drain failed instantly with a verdict of
     * TRANSIENT (the classifier's safe fallback for an exception it doesn't know).
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var agent: OllamaAgent
    private lateinit var queue: SyncQueue
    private var trigger: ConnectivityDrainTrigger? = null

    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var inputEdit: EditText

    /** Replays queued user turns against the live server; throwing re-enters the classifier. */
    private val replaySyncer = Syncer { batch: CoalescedBatch ->
        for (update in batch.updates) {
            val reply = agent.replay(update.payload)
            withContext(Dispatchers.Main) {
                appendLine("🔁 replayed \"${update.payload}\" → ${reply.take(120)}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        inputEdit = findViewById(R.id.inputEdit)

        agent = OllamaAgent(baseUrl = BASE_URL, model = MODEL)
        queue = SyncQueue.create(applicationContext)
        trigger = ConnectivityDrainTrigger(
            applicationContext, queue, replaySyncer, ioScope,
            onDrained = { report -> scope.launch { onDrained("connectivity", report) } },
        ).also { it.start() }

        findViewById<Button>(R.id.sendButton).setOnClickListener { sendCurrentInput() }
        inputEdit.setOnEditorActionListener { _, _, _ -> sendCurrentInput(); true }
        findViewById<Button>(R.id.malformedButton).setOnClickListener { sendMalformed() }
        findViewById<Button>(R.id.drainButton).setOnClickListener {
            scope.launch {
                val report = withContext(Dispatchers.IO) { queue.drain(replaySyncer) }
                onDrained("manual", report)
            }
        }
        findViewById<Button>(R.id.purgeButton).setOnClickListener {
            scope.launch {
                val purged = queue.purgeDeadLetters()
                appendLine("🧹 purged $purged dead letter(s)")
                refreshStatus()
            }
        }

        appendLine("Taper demo · model=$MODEL · server=$BASE_URL")
        appendLine("Drills: kill `ollama serve` mid-request (transient), airplane mode (queue+coalesce), Malformed req (semantic).\n")
        scope.launch { refreshStatus() }
    }

    private fun sendCurrentInput() {
        val text = inputEdit.text.toString().trim()
        if (text.isEmpty()) return
        inputEdit.text.clear()
        appendLine("🧑 $text")
        scope.launch {
            val result = withContext(Dispatchers.IO) { agent.send(text) }
            when (result) {
                is OllamaAgent.TurnResult.Reply -> {
                    result.toolTrace.forEach { appendLine("🔧 $it") }
                    appendLine("🤖 ${result.content}  (${result.tokens ?: "?"} tokens)\n")
                }
                is OllamaAgent.TurnResult.Failure -> when (result.category) {
                    FailureCategory.TRANSIENT -> {
                        queue.enqueue(CONVERSATION_ID, text)
                        appendLine("⚠️ TRANSIENT (${result.detail}) → queued for retry\n")
                    }
                    FailureCategory.SEMANTIC -> {
                        appendLine("⛔ SEMANTIC (${result.detail}) → halted, NOT retried\n")
                    }
                }
            }
            refreshStatus()
        }
    }

    private fun sendMalformed() {
        appendLine("🧨 sending deliberately malformed request…")
        scope.launch {
            val failure = withContext(Dispatchers.IO) { agent.sendMalformedRequest() }
            appendLine("⛔ classifier said ${failure.category}: ${failure.detail}\n")
            refreshStatus()
        }
    }

    private suspend fun onDrained(source: String, report: DrainReport) {
        if (report != DrainReport()) {
            appendLine(
                "📶 drain($source): synced=${report.updatesSynced} " +
                    "transient=${report.transientFailures} semantic=${report.semanticFailures}\n",
            )
        }
        refreshStatus()
    }

    private suspend fun refreshStatus() {
        val pending = queue.pendingCount()
        val dead = queue.deadLetters().size
        statusText.text = "queue: $pending pending · $dead dead"
    }

    private fun appendLine(line: String) {
        transcriptText.append(line + "\n")
        // NOT fullScroll(FOCUS_DOWN): that MOVES FOCUS to the selectable transcript,
        // silently stealing it from the input field after the first turn.
        transcriptScroll.post { transcriptScroll.smoothScrollTo(0, transcriptText.bottom) }
    }

    override fun onDestroy() {
        trigger?.stop()
        scope.cancel()
        ioScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val BASE_URL = "http://10.0.2.2:11434" // host machine, from the emulator
        const val MODEL = "qwen2.5:3b"
        const val CONVERSATION_ID = "demo-conversation"
    }
}
