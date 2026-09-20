package com.jarvis.android.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val AGENT_MAX_ROUNDS = 25
internal const val AGENT_TIMEOUT_MS = 5 * 60_000L
internal const val MAX_GOAL_CHARS = 1_000

/** Tools the agent must not call: itself (no recursion) and the one that ends the voice session. */
internal val AGENT_EXCLUDED_TOOLS = setOf("agent_task", "end_session")

internal fun agentToolDeclarations(all: List<JsonObject>): List<JsonObject> =
    all.filter { (it["name"]?.jsonPrimitive?.content ?: "") !in AGENT_EXCLUDED_TOOLS }

internal const val AGENT_DIRECTIVE =
    "[AGENT MODE]\n" +
        "You are carrying out a multi-step task on the user's phone on your own. Work in small steps with the tools: " +
        "think briefly about the next step, do ONE action, then check the result (screen_read, or screen_look when the screen has too little text) before the next. " +
        "Stop as soon as the goal is reached, and do not do more than was asked. " +
        "If something blocks you (a confirmation refused, an element you cannot find after a scroll, a permission dialog, a password), stop and say exactly what blocked you. " +
        "Never claim a step worked unless you saw it. Your final answer is a short summary in the user's language: what you did, what you saw, and anything left for the user. " +
        "You have at most $AGENT_MAX_ROUNDS tool rounds.\n"

/** The goal as it is handed to the agent, or null when it is empty. */
internal fun cleanGoal(goal: String): String? = goal.trim().take(MAX_GOAL_CHARS).ifEmpty { null }

internal fun agentSystemInstruction(base: String): String = base + "\n" + AGENT_DIRECTIVE
