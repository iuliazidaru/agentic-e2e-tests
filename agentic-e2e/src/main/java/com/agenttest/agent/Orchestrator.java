package com.agenttest.agent;

import com.agenttest.tools.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Orchestrator agent loop.
 *
 * Flow:
 *  1. Receives a high-level goal string.
 *  2. Sends it to Ollama with all registered tool definitions.
 *  3. If the LLM returns tool_calls, dispatches each to the matching AgentTool.
 *  4. Feeds tool results back to the LLM and repeats (reflection loop).
 *  5. Returns when the LLM produces a plain text response (no more tool calls).
 *
 * Human-in-the-loop:
 *  Any tool may throw HumanInputRequiredException to suspend the loop.
 *  The Orchestrator prints a prompt, waits for Enter, then resumes.
 */
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);
    private static final int    MAX_ITERATIONS = 20;

    private final OllamaClient          ollama;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ObjectMapper           mapper;

    public Orchestrator(OllamaClient ollama) {
        this.ollama = ollama;
        this.mapper = ollama.mapper();
    }

    /** Register a tool so the LLM can invoke it. */
    public Orchestrator register(AgentTool tool) {
        tools.put(tool.name(), tool);
        log.info("Registered tool: {}", tool.name());
        return this;
    }

    /**
     * Run the agent loop for a given goal.
     *
     * @param goal      high-level instruction for the LLM
     * @param systemPrompt  optional system prompt; pass null to use default
     * @return final plain-text answer from the LLM
     */
    public String run(String goal, String systemPrompt) throws Exception {
        List<JsonNode> messages = new ArrayList<>();
        List<JsonNode> toolDefs = buildToolDefinitions();

        String system = systemPrompt != null ? systemPrompt : defaultSystemPrompt();
        messages.add(ollama.systemMessage(system));
        messages.add(ollama.userMessage(goal));

        log.info("Starting agent loop — goal: {}", goal);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("Iteration {}/{}", i + 1, MAX_ITERATIONS);

            JsonNode assistantMsg = ollama.chat(messages, toolDefs);
            messages.add(assistantMsg);

            JsonNode toolCalls = assistantMsg.path("tool_calls");
            if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
                // No tool calls — LLM is done
                String finalAnswer = assistantMsg.path("content").asText();
                log.info("Agent completed. Answer: {}", finalAnswer);
                return finalAnswer;
            }

            // Dispatch every tool call in this turn
            for (JsonNode toolCall : toolCalls) {
                String toolName = toolCall.path("function").path("name").asText();
                JsonNode rawArgs = toolCall.path("function").path("arguments");

                // Arguments may arrive as a JSON string or as an object
                JsonNode args = rawArgs.isTextual()
                        ? mapper.readTree(rawArgs.asText())
                        : rawArgs;

                log.info("LLM calling tool: {} with args: {}", toolName, args);

                String result = dispatchTool(toolName, args);
                log.info("Tool result for {}: {}", toolName, result);

                messages.add(ollama.toolResultMessage(toolName, result));
            }
        }

        throw new RuntimeException("Agent exceeded max iterations (" + MAX_ITERATIONS + ") without finishing.");
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private String dispatchTool(String name, JsonNode args) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return "ERROR: Unknown tool '" + name + "'";
        }
        try {
            return tool.execute(args);
        } catch (HumanInputRequiredException e) {
            return handleHumanPause(e.getMessage());
        } catch (Exception e) {
            log.error("Tool {} threw exception: {}", name, e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    private String handleHumanPause(String prompt) {
        System.out.println("\n╔══════════════════════════════════════╗");
        System.out.println("║  HUMAN INPUT REQUIRED                ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println(prompt);
        System.out.print("Press Enter when done... ");
        System.out.flush();
        try (java.io.BufferedReader tty = new java.io.BufferedReader(
                new java.io.FileReader("/dev/tty"))) {
            tty.readLine();
        } catch (Exception e) {
            // fallback to System.in if /dev/tty is unavailable
            new Scanner(System.in).nextLine();
        }
        System.out.println("Resuming agent...\n");
        return "Human completed the required step successfully.";
    }

    private List<JsonNode> buildToolDefinitions() {
        List<JsonNode> defs = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            ObjectNode def = mapper.createObjectNode();
            def.put("type", "function");
            ObjectNode fn = def.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", tool.parametersSchema());
            defs.add(def);
        }
        return defs;
    }

    private String defaultSystemPrompt() {
        return """
                You are an E2E test automation agent. You control a browser, Outlook email, \
                and a database through the tools provided to you.
                
                Rules:
                - Use tools step by step. Complete each step before moving on.
                - After completing all steps, summarise the test result clearly: PASS or FAIL.
                - If a step fails, report it immediately with the reason.
                - Never guess data — always verify via tools.
                """;
    }

    /** Sentinel exception thrown by tools that need a human to act. */
    public static class HumanInputRequiredException extends RuntimeException {
        public HumanInputRequiredException(String message) { super(message); }
    }
}
