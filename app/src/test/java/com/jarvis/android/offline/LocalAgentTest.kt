package com.jarvis.android.offline

import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentTest {
    private fun user(t: String) = ConversationMessage(ConversationRole.USER, t, complete = true)
    private fun bot(t: String) = ConversationMessage(ConversationRole.ASSISTANT, t, complete = true)
    private fun sys(t: String) = ConversationMessage(ConversationRole.SYSTEM, t, complete = true)

    private fun tool(name: String, description: String, vararg params: Pair<String, Boolean>) =
        LocalToolSpec(name, description, params.map { (n, req) -> LocalParam(n, "the $n", req) })

    private val timer = tool("timer", "Start a countdown timer.", "duration" to true, "label" to false)
    private val lists = tool("task_list", "Manage shopping and to-do lists.", "action" to true, "item" to false)
    private val weather = tool("weather_report", "Weather forecast for a city.", "city" to false)
    private val calls = tool("call_contact", "Call a contact by name.", "name" to true)
    private val all = listOf(timer, lists, weather, calls)

    @Test fun `a gemini declaration becomes the short form, required parameters marked`() {
        val decl = buildJsonObject {
            put("name", "timer")
            put("description", "Start a timer. Longer details here.")
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("duration") { put("type", "STRING"); put("description", "How long") }
                    putJsonObject("label") { put("type", "STRING") }
                }
                putJsonArray("required") { add(JsonPrimitive("duration")) }
            }
        }
        val spec = localToolSpec(decl)!!
        assertEquals("timer", spec.name)
        assertEquals(listOf(LocalParam("duration", "How long", true), LocalParam("label", "", false)), spec.params)
        assertEquals("- timer(duration*: How long, label) : Start a timer.", describeLocalTool(spec))
        assertNull(localToolSpec(buildJsonObject { put("description", "no name") }))
    }

    @Test fun `the french words of the question pick the tool, not the others`() {
        val picked = pickLocalTools("Mets un minuteur de dix minutes", emptyList(), all, emptyList(), max = 5)
        assertEquals(listOf("timer"), picked.map { it.name })
        assertEquals(listOf("weather_report"), pickLocalTools("Quel temps fait-il à Lyon ?", emptyList(), all, emptyList(), 5).map { it.name })
        assertEquals(listOf("task_list"), pickLocalTools("Ajoute du lait à ma liste de courses", emptyList(), all, emptyList(), 5).map { it.name })
    }

    @Test fun `a follow-up still reaches the tool used just before, and nothing is offered for small talk`() {
        val picked = pickLocalTools("et du pain aussi", listOf(user("ajoute du lait à la liste"), bot("C'est ajouté.")), all, listOf("task_list"), 5)
        assertEquals(listOf("task_list"), picked.map { it.name })
        assertTrue(pickLocalTools("raconte moi une blague", emptyList(), all, emptyList(), 5).isEmpty())
        assertTrue(pickLocalTools("minuteur", emptyList(), all, emptyList(), 0).isEmpty())
    }

    @Test fun `the prompt has the rules, what is known, the tools, the conversation, and ends on the question`() {
        val prompt = buildLocalAgentPrompt(
            context = "Nous sommes le mardi.",
            history = listOf(user("Bonjour"), sys("Action : timer"), bot("Bonjour !")),
            question = "Mets un minuteur",
            tools = listOf(timer),
            steps = emptyList(),
            budget = LOCAL_BUDGET_SMALL,
        )
        assertTrue(prompt.startsWith(LOCAL_AGENT_RULES))
        assertTrue(prompt.contains("Nous sommes le mardi."))
        assertTrue(prompt.contains("- timer(duration*: the duration, label: the label)"))
        assertTrue(prompt.contains("APPEL {\"outil\""))
        assertTrue(prompt.contains("Utilisateur : Bonjour\nJarvis : Bonjour !"))
        assertFalse(prompt.contains("Action : timer"))
        assertTrue(prompt.endsWith("Utilisateur : Mets un minuteur\nJarvis :"))
    }

    @Test fun `tool results come after the question, and without tools there are no call rules`() {
        val args = buildJsonObject { put("duration", "10 min") }
        val prompt = buildLocalAgentPrompt("", emptyList(), "Mets un minuteur", emptyList(), listOf(LocalStep("timer", args, "Timer set\nfor 10 min")), 3000)
        assertFalse(prompt.contains(LOCAL_AGENT_CALL_RULES))
        assertTrue(prompt.endsWith("Utilisateur : Mets un minuteur\nAPPEL {\"outil\": \"timer\", \"args\": {\"duration\":\"10 min\"}}\nRÉSULTAT : Timer set for 10 min\nJarvis :"))
    }

    @Test fun `the prompt stays within budget by cutting the context, the old exchanges and the last tools, never the question`() {
        val history = (1..40).flatMap { listOf(user("question numéro $it " + "x".repeat(200)), bot("réponse $it " + "y".repeat(200))) }
        val tools = (1..30).map { tool("tool_$it", "Does thing $it. " + "z".repeat(100), "a" to true) }
        val prompt = buildLocalAgentPrompt("M".repeat(5000), history, "La vraie question", tools, emptyList(), LOCAL_BUDGET_SMALL)
        assertTrue("${prompt.length}", prompt.length <= LOCAL_BUDGET_SMALL)
        assertTrue(prompt.endsWith("Utilisateur : La vraie question\nJarvis :"))
        assertTrue(prompt.contains("réponse 40"))             // the newest exchange is kept
        assertFalse(prompt.contains("question numéro 1 "))    // the oldest is not
        assertTrue(prompt.contains("- tool_1("))               // the best tools are kept first
    }

    @Test fun `a call is read in the asked format and in the ones small models write instead`() {
        val known = setOf("timer", "task_list")
        val asked = parseLocalOutput("APPEL {\"outil\": \"timer\", \"args\": {\"duration\": \"10 min\"}}", known)
        assertEquals(LocalOutput.Call("timer", buildJsonObject { put("duration", "10 min") }), asked)
        val qwen = parseLocalOutput("<tool_call>\n{\"name\": \"task_list\", \"arguments\": {\"action\": \"add\", \"item\": \"lait\", \"count\": 2}}\n</tool_call>", known)
        assertEquals(LocalOutput.Call("task_list", buildJsonObject { put("action", "add"); put("item", "lait"); put("count", 2) }), qwen)
        val fenced = parseLocalOutput("```json\n{\"tool\": \"timer\", \"args\": {\"duration\": \"5 min\", \"x\": {\"y\": 1}}}\n```", known)
        assertEquals(LocalOutput.Call("timer", buildJsonObject { put("duration", "5 min"); put("x", "{\"y\":1}") }), fenced)
        val bare = parseLocalOutput("{\"outil\": \"timer\", \"args\": \"{\\\"duration\\\": \\\"1 h\\\"}\"}", known)
        assertEquals(LocalOutput.Call("timer", buildJsonObject { put("duration", "1 h") }), bare)
        val noArgs = parseLocalOutput("APPEL {\"outil\": \"timer\"}", known) as LocalOutput.Call
        assertEquals(JsonObject(emptyMap()), noArgs.args)
    }

    @Test fun `anything else is an answer, cleaned of what the model echoed`() {
        assertEquals(LocalOutput.Answer("Il est midi."), parseLocalOutput("Jarvis : Il est midi.", setOf("timer")))
        assertEquals(LocalOutput.Answer("Je ne peux pas."), parseLocalOutput("Je ne peux pas.\nAPPEL {\"outil\": \"inconnu\"}", setOf("timer")))
        assertEquals(LocalOutput.Answer("Voilà.\n".trim()), parseLocalOutput("Voilà.\nUtilisateur : et après ?", setOf("timer")))
        assertEquals(LocalOutput.Answer("{ pas du json"), parseLocalOutput("{ pas du json", setOf("timer")))
    }

    @Test fun `the agent runs the tool the model asks for, then answers with its result`() = runBlocking {
        val prompts = mutableListOf<String>()
        val replies = ArrayDeque(listOf("APPEL {\"outil\": \"timer\", \"args\": {\"duration\": \"10 min\"}}", "C'est parti pour dix minutes."))
        val ran = mutableListOf<Pair<String, JsonObject>>()
        val steps = mutableListOf<String>()
        val result = runLocalAgent(
            question = "Mets un minuteur de 10 minutes",
            history = listOf(user("Salut"), bot("Bonjour")),
            context = "",
            tools = listOf(timer),
            budget = LOCAL_BUDGET_SMALL,
            generate = { p -> prompts += p; LocalReply(replies.removeFirst(), null) },
            runTool = { name, args -> ran += name to args; "Timer started: 10 min." },
            onStep = { steps += it },
        )
        assertEquals("C'est parti pour dix minutes.", result.text)
        assertEquals(listOf("timer" to buildJsonObject { put("duration", "10 min") }), ran)
        assertEquals(listOf("timer"), steps)
        assertTrue(prompts[1].contains("RÉSULTAT : Timer started: 10 min."))
        assertTrue(prompts[1].contains("Utilisateur : Salut"))
    }

    @Test fun `a looping or failing model still ends with something to say`() = runBlocking {
        val call = "APPEL {\"outil\": \"timer\", \"args\": {\"duration\": \"1 min\"}}"
        var runs = 0
        val looping = runLocalAgent("minuteur", emptyList(), "", listOf(timer), 3000, { LocalReply(call, null) }, { _, _ -> runs++; "Timer started." })
        assertEquals("Timer started.", looping.text)
        assertEquals(1, runs)

        var n = 0
        val endless = runLocalAgent("minuteur", emptyList(), "", listOf(timer), 3000,
            { LocalReply("APPEL {\"outil\": \"timer\", \"args\": {\"duration\": \"${++n} min\"}}", null) }, { _, a -> "ok ${a["duration"]}" })
        assertEquals(LOCAL_AGENT_MAX_STEPS, endless.steps.size)

        val silent = runLocalAgent("bonjour", emptyList(), "", emptyList(), 3000, { LocalReply(null, "trop long") }, { _, _ -> "" })
        assertNull(silent.text)
        assertEquals("trop long", silent.reason)

        val broken = runLocalAgent("minuteur", emptyList(), "", listOf(timer), 3000,
            { p -> LocalReply(if ("RÉSULTAT : " in p) "" else call, null) }, { _, _ -> throw IllegalStateException("boom") })
        assertEquals("Tool 'timer' failed: boom", broken.text)
    }

    @Test fun `the last tools are read from the chat's action lines, newest first`() {
        val messages = listOf(sys("Action : timer"), user("a"), sys("Action: task_list"), sys("Action : timer"), sys("autre chose"))
        assertEquals(listOf("timer", "task_list"), recentToolNames(messages))
    }
}
