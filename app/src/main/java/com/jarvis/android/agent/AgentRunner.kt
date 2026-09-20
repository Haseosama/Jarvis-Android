package com.jarvis.android.agent

import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import com.jarvis.android.JarvisContainer
import com.jarvis.android.actions.ToolRegistry
import com.jarvis.android.core.buildSystemInstruction
import com.jarvis.android.rest.RestChatException
import com.jarvis.android.rest.RestChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Runs one multi-step task in the background with the same tools as the assistant. It is started
 * by the `agent_task` tool, which returns at once so the conversation stays usable; when the task
 * ends its summary is spoken (if a voice session is open) and posted in the text chat.
 * Sensitive actions still need the user's confirmation, exactly as for direct tool calls.
 */
internal class AgentRunner(private val container: JarvisContainer, private val scope: CoroutineScope) {
    @Volatile private var job: Job? = null
    @Volatile private var goal: String = ""
    @Volatile private var steps = 0

    val running: Boolean get() = job?.isActive == true

    fun status(): String =
        if (running) "Tâche en cours : « $goal » ($steps action(s) effectuée(s))." else "Aucune tâche en cours."

    /** Starts [rawGoal]; returns what to tell the assistant. */
    @Synchronized
    fun start(rawGoal: String): String {
        val cleaned = cleanGoal(rawGoal) ?: return "Indiquez l’objectif de la tâche."
        if (running) return "Une tâche est déjà en cours : « $goal ». Annulez-la d’abord (action cancel)."
        goal = cleaned
        steps = 0
        job = scope.launch { execute(cleaned) }
        return "Tâche lancée en arrière-plan : « $cleaned ». Dites à l’utilisateur qu’elle est en cours ; vous serez informé de la fin."
    }

    @Synchronized
    fun cancel(): String {
        val current = job
        if (current == null || !current.isActive) return "Aucune tâche en cours."
        current.cancel()
        return "Tâche annulée."
    }

    /** Runs [task] to the end and returns the outcome text, without announcing it anywhere. */
    suspend fun runOnce(task: String): String {
        val session = RestChatSession(
            transport = container.restChat.transport,
            model = { container.configStore.snapshotRestModel() },
            systemInstruction = { agentSystemInstruction(buildSystemInstruction(container, textMode = true)) },
            toolDeclarations = { agentToolDeclarations(ToolRegistry.declarations()) },
            runTool = { name, args ->
                steps++
                container.log("Agent : $name")
                if (name in AGENT_EXCLUDED_TOOLS) "Action '$name' interdite dans une tâche automatique."
                else ToolRegistry.run(name, args, container)
            },
            maxRounds = AGENT_MAX_ROUNDS,
        )
        return try {
            withTimeout(AGENT_TIMEOUT_MS) { tr("Tâche terminée : ") + session.send("Objectif : $task") }
        } catch (e: TimeoutCancellationException) {
            trf("Tâche interrompue : trop longue (5 minutes maximum) après {0} action(s).", steps)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestChatException) {
            trf("Tâche interrompue : {0}", e.message)
        } catch (_: Exception) {
            tr("Tâche interrompue : erreur inattendue.")
        }
    }

    private suspend fun execute(task: String) {
        val outcome = runOnce(task)
        container.log(outcome.take(160))
        container.restChat.postAssistant(outcome)
        container.engine.announce(outcome.take(600))
    }
}
