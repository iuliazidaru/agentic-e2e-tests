package com.agenttest.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Contract for every tool the agent can invoke.
 *
 * Each tool is registered with the Orchestrator and its definition
 * is serialised into the Ollama "tools" payload so the LLM can call it.
 */
public interface AgentTool {

    /** Unique name the LLM uses to call this tool. */
    String name();

    /** Human-readable description sent to the LLM. */
    String description();

    /**
     * JSON Schema describing the parameters object.
     * Example:
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "selector": { "type": "string", "description": "CSS selector" }
     *   },
     *   "required": ["selector"]
     * }
     * </pre>
     */
    ObjectNode parametersSchema();

    /**
     * Execute the tool with the arguments the LLM provided.
     *
     * @param args parsed JSON arguments from the LLM tool_call
     * @return plain-text result sent back to the LLM as a tool message
     */
    String execute(JsonNode args) throws Exception;
}
