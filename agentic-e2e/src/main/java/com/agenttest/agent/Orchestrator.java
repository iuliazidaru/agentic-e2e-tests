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
                String finalAnswer = assistantMsg.path("content").asText("");

                // If the model gave an empty response on iteration 1 without calling tools,
                // nudge it to use the registered tools — but only if there are tools that
                // could plausibly serve the request.
                if (finalAnswer.isBlank() && i == 0 && !tools.isEmpty()) {
                    log.warn("Model returned empty message on iteration 1 without calling tools. Retrying with explicit tool instruction...");
                    messages.add(ollama.userMessage("You must call one of the available tools (e.g. database or browser) to complete the request. Do not reply empty."));
                    continue;
                }

                // If the model gave a non-empty answer but forgot PASS/FAIL, give it one
                // chance to restate — but only once (i == 1 guards against infinite loop).
                if (!finalAnswer.isBlank()
                        && !containsVerdict(finalAnswer)
                        && i == 1) {
                    log.warn("Model response lacks PASS/FAIL verdict. Requesting restatement...");
                    messages.add(ollama.userMessage(
                            "Your response must start with PASS or FAIL. " +
                            "Restate your answer starting with exactly PASS: or FAIL:."));
                    continue;
                }

                // Fall back to tool results if final answer is blank or still missing a verdict
                if (finalAnswer.isBlank() || !containsVerdict(finalAnswer)) {
                    for (int m = messages.size() - 1; m >= 0; m--) {
                        JsonNode msg = messages.get(m);
                        if ("tool".equals(msg.path("role").asText())) {
                            String toolContent = msg.path("content").asText("");
                            if (containsVerdict(toolContent)) {
                                log.info("Falling back to tool result as final answer: {}", toolContent);
                                finalAnswer = toolContent;
                                break;
                            }
                        }
                    }
                }

                // If still blank, the model never used a tool and produced no answer.
                // This usually means a required tool (e.g. "database") was not registered.
                if (finalAnswer.isBlank()) {
                    finalAnswer = "FAIL: Agent produced no answer. "
                            + "Required tool may not be registered (registered tools: " + tools.keySet() + ").";
                    log.warn("Agent returned blank answer — returning synthetic FAIL: {}", finalAnswer);
                }

                log.info("Agent completed. Answer: {}", finalAnswer);
                return finalAnswer;
            }

            // Dispatch every tool call in this turn
            StringBuilder executedResults = new StringBuilder();
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

                executedResults.append(result).append("\n");
                messages.add(ollama.toolResultMessage(toolName, result));
            }

            // If tool results already contain a verdict and the assistant message also has
            // non-empty content, only short-circuit when that content contains the verdict.
            // Otherwise keep looping so the LLM can emit a proper PASS/FAIL summary.
            if (containsVerdict(executedResults.toString())) {
                String content = assistantMsg.path("content").asText("");
                if (!content.isBlank() && containsVerdict(content)) {
                    return content;
                }
            }
        }

        throw new RuntimeException("Agent exceeded max iterations (" + MAX_ITERATIONS + ") without finishing.");
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Returns true if the text contains a PASS or FAIL verdict.
     * Requires the word to appear at the start of a line or after whitespace/punctuation,
     * so that "PASS" buried inside an error stack-trace or Playwright log does not match.
     */
    private static final java.util.regex.Pattern VERDICT_PATTERN =
            java.util.regex.Pattern.compile("(?:^|[\\s:,])(?:PASS|FAIL)(?:[:\\s,!.]|$)",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.MULTILINE);

    private static boolean containsVerdict(String text) {
        if (text == null || text.isBlank()) return false;
        // Error responses from dispatchTool always start with "ERROR:" — never a verdict.
        if (text.startsWith("ERROR:")) return false;
        return VERDICT_PATTERN.matcher(text).find();
    }

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
        // Open /dev/tty directly so we always read from the real terminal,
        // even when Maven Surefire has redirected System.in to a null stream.
        try (java.io.InputStream tty = new java.io.FileInputStream("/dev/tty");
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(tty))) {
            reader.readLine();
        } catch (Exception e) {
            log.warn("Could not open /dev/tty for human pause ({}). " +
                     "Run with -DforkCount=0 or set BROWSER_HEADLESS=false " +
                     "and interact via the browser.", e.getMessage());
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
                You are an expert E2E test automation agent.
                You MUST use the provided tools to execute the steps in the user goal.

                For database actions:
                - Call the `database` tool with parameters `action` (e.g. "assertRowExists", "query") and `sql`.

                For browser actions:
                - Call the `browser` tool with parameters `action` (e.g. "navigate", "click", "waitForLogin") and the required parameters.

                CRITICAL RULE FOR FINAL ANSWER:
                After all tools have been called, your final message MUST start with exactly the word PASS or FAIL in uppercase, followed by a colon and a brief reason.
                Example of correct final answer: "PASS: All steps completed successfully."
                Example of correct final answer: "FAIL: Step 2 failed because element was not found."
                Do NOT use any other format. Do NOT write a paragraph without starting with PASS or FAIL.
                """;
    }

    /** Sentinel exception thrown by tools that need a human to act. */
    public static class HumanInputRequiredException extends RuntimeException {
        public HumanInputRequiredException(String message) { super(message); }
    }
}
