# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Build the project
mvn compile

# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=OrderFlowTest

# Run a specific test method
mvn test -Dtest=OrderFlowTest#placeOrder_shouldSendConfirmationEmailAndPersistToDb

# Install Playwright browsers (required before first browser test run)
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

## Prerequisites

- **Ollama** must be running locally at `http://localhost:11434` with a tool-calling capable model (default: `qwen2.5:14b`)
- **Java 17+** and **Maven**

### Starting Ollama with Podman

A `docker-compose.yml` is provided in the project root:

```bash
# Start Ollama
podman-compose up -d

# Pull the model (first time only)
podman exec ollama ollama pull qwen2.5:14b

# Stop Ollama
podman-compose down
```

Model data is stored in a named volume (`ollama`) so it persists across restarts.

> **Apple Silicon:** Podman containers cannot use the GPU on Apple Silicon. For faster inference on M1/M2/M3 Macs, install Ollama natively from [ollama.com](https://ollama.com/download) and run `ollama serve`.

#### Troubleshooting: not enough memory

If you get `model requires more system memory than is available`, Podman's VM memory limit is too low.

**Option 1 — Increase Podman VM memory (recommended):**
```bash
podman machine stop
podman machine set --memory 10240
podman machine start
podman-compose up -d
```

**Option 2 — Use a smaller model (~1 GB):**
```bash
podman exec ollama ollama pull qwen2.5:1.5b
```
Then set in `.env`:
```
OLLAMA_MODEL=qwen2.5:1.5b
```
> `qwen2.5:1.5b` supports tool calling but is less capable. Prefer Option 1 for real test scenarios.

## Environment Variables

| Variable | Purpose | Required |
|----------|---------|----------|
| `APP_BASE_URL` | Base URL of the application under test (default: `https://myapp.local`) | No |
| `OLLAMA_BASE_URL` | Ollama server URL (default: `http://localhost:11434`) | No |
| `OLLAMA_MODEL` | LLM model name (default: `qwen2.5:14b`) | No |
| `BROWSER_HEADLESS` | `true`/`false` for headless browser mode | No |
| `DB_JDBC_URL` | JDBC connection string | No (disables DB tool) |
| `DB_USERNAME` | Database username | No |
| `DB_PASSWORD` | Database password | No |
| `AZURE_TENANT_ID` | Azure AD tenant for Microsoft Graph | No (disables mail tool) |
| `AZURE_CLIENT_ID` | Azure AD app client ID | No |
| `AZURE_CLIENT_SECRET` | Azure AD app client secret | No |
| `MAIL_USER_ID` | Outlook mailbox user ID/email | No |

Tools are silently disabled when their env vars are absent — no error is thrown.

### Using a .env File

Create a `.env` file in the project root (never commit it — add to `.gitignore`):

```bash
APP_BASE_URL=https://myapp.example.com
BROWSER_HEADLESS=true
DB_JDBC_URL=jdbc:postgresql://localhost:5432/mydb
DB_USERNAME=myuser
DB_PASSWORD=secret
AZURE_TENANT_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
AZURE_CLIENT_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
AZURE_CLIENT_SECRET=your-secret
MAIL_USER_ID=tester@mycompany.com
```

Source it before running Maven:

```bash
set -a && source .env && set +a && mvn test
```



## Architecture

This is an **agentic E2E testing framework** where an LLM orchestrates browser, email, and database interactions to execute test scenarios described in plain English.

### Core Agent Loop (`Orchestrator.java`)

The `Orchestrator` implements a standard LLM tool-use reflection loop:
1. Receives a plain-English goal
2. Sends goal + tool definitions to Ollama
3. Dispatches tool calls returned by the LLM
4. Feeds results back into the conversation
5. Repeats (max 20 iterations) until the LLM returns a final answer

Human-in-the-loop login is supported: `BrowserTool`'s `waitForLogin` action throws `HumanInputRequiredException`, which pauses the loop until the user presses Enter after manually completing login.

### Tools (`AgentTool` interface)

All tools implement `AgentTool` — `name()`, `description()`, `parametersSchema()` (JSON Schema), and `execute(JsonNode args)`. Ollama receives OpenAI-compatible tool definitions built dynamically from registered tools.

- **`BrowserTool`**: Playwright/Chromium wrapper. Actions: `navigate`, `click`, `fill`, `select`, `getText`, `getPageText`, `screenshot`, `waitForSelector`, `waitForLogin`. Exposes `page()` for direct Playwright assertions in tests.
- **`MailTool`**: Microsoft Graph SDK for Outlook. Actions: `sendMail`, `listMessages`, `readMessage`, `waitForMessage` (polls every 5s up to 120s).
- **`DatabaseTool`**: JDBC executor. Actions: `query` (returns JSON), `assertRowExists`, `assertCount`, `execute`. Assertions return `"PASS"` or `"FAIL"` strings for the LLM to interpret.

### Writing Tests

Extend `AgentTestBase` (JUnit 5 `@TestInstance(PER_CLASS)`). Call `run(goal)` with an English description of what the agent should do. Assert the returned string with AssertJ:

```java
class MyTest extends AgentTestBase {
    @Test
    void myScenario() {
        String result = run("Navigate to http://example.com and verify the title says 'Example Domain'");
        assertThat(result).containsIgnoringCase("PASS");
    }
}
```

`AgentTestBase` initializes tools in `@BeforeAll` and tears them down in `@AfterAll`. Each `run()` call creates a fresh `Orchestrator` with the same shared tool instances (browser session persists across test methods within a class).

### Adding Custom Tools

Implement `AgentTool` and register it with the Orchestrator in `AgentTestBase` or override `run()`:

```java
orchestrator.registerTool(new MyCustomTool());
```

### Database Driver Setup

PostgreSQL and MSSQL JDBC drivers are commented out in `pom.xml`. Uncomment the appropriate dependency before using `DatabaseTool`.
