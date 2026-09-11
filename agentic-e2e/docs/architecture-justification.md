# Architecture Justification: Custom Agentic Framework vs. MCP

## Executive Summary

This document justifies the architectural decision to implement the agentic E2E testing framework as a **self-contained, in-process agent loop** rather than adopting the **Model Context Protocol (MCP)** architecture. The choice is driven by the requirements of automated software testing: determinism, CI/CD integration, JVM lifecycle control, and verifiable pass/fail outcomes. MCP solves a different problem — interactive, multi-client tool reuse — and introducing it here would add protocol overhead without delivering any of those capabilities.

---

## 1. What This Framework Does

The framework enables E2E test scenarios to be described in plain English and executed by an LLM that autonomously drives a browser, queries a database, and reads email inboxes. The architecture has three layers:

### 1.1 Tool Layer

Each integration is encapsulated in a class implementing the `AgentTool` interface:

```
AgentTool
├── name()               → unique identifier the LLM uses to call the tool
├── description()        → natural-language description sent to the LLM
├── parametersSchema()   → JSON Schema for the tool's arguments
└── execute(JsonNode)    → executes the action, returns a plain-text result
```

Concrete implementations:

| Tool | Technology | Capabilities |
|------|-----------|-------------|
| `BrowserTool` | Playwright/Chromium | navigate, click, fill, getText, screenshot, waitForLogin |
| `DatabaseTool` | JDBC | query, assertRowExists, assertCount, execute |
| `MailTool` | Microsoft Graph SDK | sendMail, listMessages, readMessage, waitForMessage |

### 1.2 Agent Layer (`Orchestrator`)

`Orchestrator` implements the standard LLM **tool-use reflection loop**:

```
┌─────────────────────────────────────────────────────────────┐
│                        Orchestrator                         │
│                                                             │
│  goal ──► [build tool definitions]                         │
│               │                                             │
│               ▼                                             │
│         OllamaClient.chat(messages, toolDefs)               │
│               │                                             │
│         ┌─────▼──────────────────────────┐                 │
│         │  LLM response has tool_calls?  │                 │
│         └─────┬──────────────┬───────────┘                 │
│              YES             NO                             │
│               │               └──► return final answer     │
│               ▼                                             │
│         dispatch to AgentTool.execute()                     │
│               │                                             │
│               ▼                                             │
│         append tool result to messages                      │
│               │                                             │
│               └──────────────────────────► repeat (max 20) │
└─────────────────────────────────────────────────────────────┘
```

The loop also handles **human-in-the-loop** pauses: any tool may throw `HumanInputRequiredException`, which suspends execution and waits for a keypress before resuming — enabling manual SSO/MFA login steps in otherwise automated flows.

### 1.3 Test Layer (`AgentTestBase`)

JUnit 5 manages the test lifecycle:

- `@BeforeAll` — starts browser, connects to DB, initialises mail client
- Each `@Test` — creates a fresh `Orchestrator` (clean conversation history) with the shared tool instances
- `@AfterAll` — closes browser and DB connections

Test authors write goals in plain English and assert the returned string:

```java
String result = run("Navigate to /orders and verify the most recent order has status CONFIRMED");
assertThat(result).containsIgnoringCase("PASS");
```

---

## 2. What MCP Is

The **Model Context Protocol** (MCP) is an open standard that defines how LLM clients discover and call tools hosted in separate processes, connected via JSON-RPC over stdio, SSE, or HTTP.

```
┌─────────────────┐        JSON-RPC / stdio        ┌──────────────────┐
│   MCP Client    │ ◄──────────────────────────────► │   MCP Server     │
│ (Claude Desktop,│   tools/list → tool definitions  │ (separate proc.) │
│  VS Code, etc.) │   tools/call → tool execution    │                  │
└─────────────────┘                                  └──────────────────┘
```

Key properties:
- Tools live in **separate processes** (or remote servers), isolated from the client
- The **LLM client** (e.g. Claude Desktop) drives the orchestration — there is no embedded loop
- Tools are discovered at runtime via `tools/list`; any MCP-compatible client can use them
- Transport is **protocol-defined**: JSON-RPC with a handshake, capability negotiation, and versioning

---

## 3. Side-by-Side Comparison

| Dimension | Custom Framework | MCP |
|-----------|-----------------|-----|
| **Tool execution** | Direct Java method call in the same JVM | JSON-RPC call to a separate process |
| **Orchestration** | `Orchestrator` + Ollama, embedded in test process | External LLM client |
| **Transport overhead** | None — in-process | JSON-RPC serialisation, process boundary, IPC |
| **LLM** | Any Ollama-compatible model (configurable) | Whatever client the user connects |
| **Test runner integration** | Native JUnit 5 — `mvn test`, CI pipelines, reports | No native concept of a test run |
| **Pass/Fail assertion** | AssertJ on the returned string, standard JUnit result | No assertion mechanism |
| **Lifecycle management** | `@BeforeAll`/`@AfterAll` for browser/DB setup | Client-managed; no teardown guarantee |
| **Shared browser session** | Single Playwright instance across `@Test` methods in a class | Not possible across tool calls |
| **Human-in-the-loop login** | `HumanInputRequiredException` pauses the loop | Not supported by protocol |
| **Portability** | Tied to this framework | Any MCP-compatible client |
| **Operational complexity** | Single process, no daemons required | Requires running MCP server process(es) |
| **Debugging** | SLF4J logs, IDE debugger, breakpoints in tool code | Logs split across client and server process |
| **Security boundary** | Single JVM, same credentials context | Tools isolated in separate process |

---

## 4. Why MCP Would Be a Poor Fit Here

### 4.1 No Test Lifecycle Control

MCP has no concept of `@BeforeAll` / `@AfterAll`. There is no mechanism to:
- Start a Playwright browser before a test suite and close it after
- Open a JDBC connection once and reuse it across test steps
- Guarantee teardown even when a test fails

Each MCP tool invocation is stateless from the protocol's perspective. Maintaining browser session state across tool calls in an MCP server requires workarounds (e.g., a session-ID parameter on every call) that the framework handles naturally by holding `BrowserTool` as a field in `AgentTestBase`.

### 4.2 No Pass/Fail Assertion Mechanism

E2E tests must produce a binary outcome: pass or fail. The JUnit integration in this framework delivers that directly:

```java
assertThat(result).containsIgnoringCase("PASS");
// ↑ JUnit marks the test as FAILED if this throws, feeding CI/CD pipelines
```

MCP clients return tool results to the LLM — they do not return a final answer to a calling test method. There is no clean way to translate an LLM's conversational output into a JUnit assertion without re-embedding an orchestration loop — at which point the MCP protocol adds only overhead.

### 4.3 Protocol Overhead Has No Payoff

MCP's JSON-RPC transport, process boundary, and capability negotiation handshake are worthwhile costs when the goal is **multi-client reusability**: the same tool server can be used by Claude Desktop, VS Code, a web app, and a CLI simultaneously. This framework has exactly one consumer: the `Orchestrator` running inside the test process. The protocol boundary delivers no value and adds latency, complexity, and an extra failure domain.

### 4.4 Human-in-the-Loop Would Break

`BrowserTool.waitForLogin` throws `HumanInputRequiredException`, which the `Orchestrator` catches to pause execution and read from `/dev/tty`. This is only possible because the tools and the orchestration loop share a process. In an MCP architecture, the tool call would timeout waiting for a response from the server, and there is no channel to communicate the pause back to a user sitting in front of a terminal.

### 4.5 LLM Choice Would Be Delegated to the Client

Currently the framework tests with `qwen2.5:14b` via Ollama — a deliberate choice to avoid cloud costs, support air-gapped environments, and keep all inference local. With MCP, the LLM is controlled by whichever client connects. Maintaining LLM selection as a project-level configuration (via `OLLAMA_MODEL` env var) is critical for reproducible test results; MCP removes that control.

### 4.6 Debugging Complexity

When a test fails, developers need a single log stream, IDE breakpoints in tool code, and a clear call stack. With MCP, logs are split between the client process (LLM reasoning) and the server process (tool execution), tool breakpoints require attaching a second debugger, and errors crossing the process boundary are surfaced as JSON error objects rather than Java exceptions.

---

## 5. Where MCP Would Be the Right Choice

This architecture critique is use-case specific. MCP would be the correct choice if:

- The **tools need to be reused across multiple LLM clients** (e.g., both Claude Desktop for exploratory testing and an automated pipeline)
- The **browser/mail/DB tools should be shared infrastructure** consumed by different teams' agents
- **Interactive, conversational use** is the primary mode (e.g., a developer asking Claude Desktop to "check whether my latest deployment broke the login flow")
- **Cross-language reuse** is required (a Python service consuming the same tools as a Java service)

In those scenarios, the MCP overhead is justified by the reusability it enables.

---

## 6. Architecture Decision Record

**Decision:** Implement the E2E testing agent as a self-contained in-process framework with a custom tool-use loop, not as an MCP server.

**Context:** The framework must integrate with JUnit 5, produce pass/fail results consumable by CI/CD pipelines, manage stateful resources (browser session, DB connection) across test methods, and support human-in-the-loop pauses for SSO login. All inference runs locally via Ollama.

**Chosen approach:** `Orchestrator` + `AgentTool` interface + `AgentTestBase` JUnit base class, all in-process.

**Rejected alternative:** MCP server exposing `BrowserTool`, `MailTool`, `DatabaseTool` over JSON-RPC.

**Rationale:** MCP solves multi-client tool reuse across process boundaries. This framework has a single consumer (the test runner) and requires tight lifecycle control, deterministic assertion, and a shared process boundary for human-in-the-loop interaction. MCP would add protocol overhead while making the core testing requirements harder to satisfy.

**Consequences:**
- The framework is not directly reusable by other MCP-compatible clients without porting
- All inference is local (Ollama); there is no built-in path to cloud LLMs
- Adding new tools requires implementing `AgentTool` in Java and registering them in `AgentTestBase`

---

## 7. Summary Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                        JVM Process                               │
│                                                                  │
│  ┌───────────────────┐     ┌──────────────────────────────────┐  │
│  │  JUnit 5 Runner   │     │         Orchestrator             │  │
│  │                   │     │  (tool-use reflection loop)      │  │
│  │  @BeforeAll ──────┼────►│                                  │  │
│  │  @Test     ──────►│     │  ┌───────────┐  ┌────────────┐  │  │
│  │  @AfterAll ◄──────┼─────│  │BrowserTool│  │DatabaseTool│  │  │
│  │                   │     │  │(Playwright)│  │  (JDBC)    │  │  │
│  │  assertThat(result│     │  └───────────┘  └────────────┘  │  │
│  │    ).containsIgn  │     │  ┌───────────┐                   │  │
│  │    oringCase("PASS│     │  │ MailTool  │                   │  │
│  └───────────────────┘     │  │(MS Graph) │                   │  │
│                            │  └───────────┘                   │  │
│                            └───────────────────────┬──────────┘  │
│                                                    │HTTP         │
└────────────────────────────────────────────────────┼─────────────┘
                                                     ▼
                                           ┌──────────────────┐
                                           │  Ollama (local)  │
                                           │  qwen2.5:14b     │
                                           └──────────────────┘
```

Everything runs in a single JVM process. The test runner has direct control over the agent loop, tool lifecycle, and assertion outcome — the properties that matter most for reliable, CI-integrated automated testing.
