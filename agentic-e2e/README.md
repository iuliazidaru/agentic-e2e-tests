# Agentic E2E Testing Framework

Java-based E2E testing framework using agentic patterns:
**Orchestrator + subagents**, **tool-use agents**, **multi-agent collaboration**.

All LLM inference runs locally via Ollama — no cloud, no public agent framework.

---

## Architecture

```
Orchestrator (agent loop)
├── BrowserTool     → Playwright for Java (UI clicks, navigation, login pause)
├── MailTool        → Microsoft Graph SDK (send / receive Outlook email)
└── DatabaseTool    → JDBC (query and assert DB state)

Local LLM: Ollama (qwen2.5:14b or mistral-nemo)
```

---

## Prerequisites

### 1. Install Ollama
```bash
# macOS / Linux
curl -fsSL https://ollama.com/install.sh | sh

# Windows: download from https://ollama.com/download
```

### 2. Pull a model with tool-calling support
```bash
ollama pull qwen2.5:14b    # recommended (~9GB)
# or lighter option:
ollama pull mistral-nemo   # (~7GB)
```

### 3. Install Playwright browsers
```bash
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

---

## Configuration

Set environment variables (or add to your CI pipeline / `.env`):

```bash
# LLM
export OLLAMA_BASE_URL=http://localhost:11434
export OLLAMA_MODEL=qwen2.5:14b

# Browser
export BROWSER_HEADLESS=false       # false = visible browser window

# Database (pick your driver and add it to pom.xml)
export DB_JDBC_URL=jdbc:postgresql://localhost:5432/mydb
export DB_USERNAME=myuser
export DB_PASSWORD=secret

# Outlook / Microsoft Graph
# Register an app at https://portal.azure.com → Azure AD → App registrations
# Grant: Mail.Send + Mail.ReadWrite (Application permissions)
export AZURE_TENANT_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
export AZURE_CLIENT_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
export AZURE_CLIENT_SECRET=your-secret
export MAIL_USER_ID=tester@yourcompany.com
```

---

## Project structure

```
src/
├── main/java/com/agenttest/
│   ├── agent/
│   │   ├── OllamaClient.java      ← HTTP wrapper for Ollama REST API
│   │   └── Orchestrator.java      ← Agent loop with tool dispatch + reflection
│   └── tools/
│       ├── AgentTool.java         ← Tool interface (implement to add new tools)
│       ├── BrowserTool.java       ← Playwright tool (UI + human login pause)
│       ├── MailTool.java          ← Microsoft Graph mail tool
│       └── DatabaseTool.java      ← JDBC database tool
└── test/java/com/agenttest/
    ├── AgentTestBase.java         ← JUnit 5 base class (wires everything)
    └── OrderFlowTest.java         ← Example test scenarios
```

---

## Running tests

```bash
# Run all tests
mvn test

# Run a specific test
mvn test -Dtest=OrderFlowTest#placeOrder_shouldSendConfirmationEmailAndPersistToDb
```

---

## Writing your own tests

Extend `AgentTestBase` and call `run(goal)` with a plain-English description:

```java
class MyFeatureTest extends AgentTestBase {

    @Test
    void myScenario() throws Exception {
        String result = run("""
            STEP 1 — Navigate to the dashboard at https://myapp.local/dashboard.
            STEP 2 — Click selector="#new-report-btn".
            STEP 3 — Fill selector="#report-name" with value="Monthly Report".
            STEP 4 — Click selector="#submit-btn".
            STEP 5 — Verify the database has a row:
                     sql="SELECT id FROM reports WHERE name='Monthly Report'"
            Report PASS or FAIL.
        """);

        assertThat(result).containsIgnoringCase("PASS");
    }
}
```

### Human-in-the-loop login

Any test can pause for manual login:

```java
// In your goal string:
"""
Use browser action=waitForLogin and url=https://myapp.local/login.
The test will pause and print a prompt in the console.
After you log in, press Enter to resume.
...
"""
```

### Adding a new tool

Implement `AgentTool` and register it:

```java
public class MyCustomTool implements AgentTool {
    @Override public String name()        { return "myTool"; }
    @Override public String description() { return "Does X and Y."; }
    @Override public ObjectNode parametersSchema() { /* JSON Schema */ }
    @Override public String execute(JsonNode args)  { /* logic */ }
}

// In your test or AgentTestBase:
orchestrator.register(new MyCustomTool());
```

---

## Adding a database driver

Uncomment the relevant dependency in `pom.xml`:

```xml
<!-- PostgreSQL -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>

<!-- MSSQL -->
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
    <version>12.6.1.jre11</version>
</dependency>
```
