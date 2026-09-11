package com.agenttest.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Thin wrapper around Ollama's /api/chat endpoint.
 *
 * No SDK — just java.net.http.  Ollama must be running locally:
 *   ollama serve
 *   ollama pull qwen2.5:14b
 */
public class OllamaClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL    = "qwen2.5:14b";

    private final HttpClient  http;
    private final ObjectMapper mapper;
    private final String       baseUrl;
    private final String       model;

    public OllamaClient() {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL);
    }

    public OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model   = model;
        this.mapper  = new ObjectMapper();
        this.http    = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Send a chat request to Ollama and return the assistant's message node.
     *
     * @param messages list of {role, content} or {role, content, tool_calls} nodes
     * @param tools    list of tool definition nodes (may be empty)
     * @return the full assistant message JsonNode from the response
     */
    public JsonNode chat(List<JsonNode> messages, List<JsonNode> tools) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.put("stream", false);

        ArrayNode msgArray = payload.putArray("messages");
        messages.forEach(msgArray::add);

        if (!tools.isEmpty()) {
            ArrayNode toolArray = payload.putArray("tools");
            tools.forEach(toolArray::add);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama error " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        return root.path("message");
    }

    // ── Convenience message builders ──────────────────────────────────────────

    public ObjectNode systemMessage(String content) {
        return message("system", content);
    }

    public ObjectNode userMessage(String content) {
        return message("user", content);
    }

    public ObjectNode toolResultMessage(String toolName, String result) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "tool");
        msg.put("name", toolName);
        msg.put("content", result);
        return msg;
    }

    private ObjectNode message(String role, String content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    public ObjectMapper mapper() { return mapper; }
    public String model()        { return model; }
}
