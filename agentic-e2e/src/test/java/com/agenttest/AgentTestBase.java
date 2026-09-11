package com.agenttest;

import com.agenttest.agent.OllamaClient;
import com.agenttest.util.EnvTemplate;
import com.agenttest.agent.Orchestrator;
import com.agenttest.tools.BrowserTool;
import com.agenttest.tools.DatabaseTool;
import com.agenttest.tools.MailTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all agentic E2E tests.
 *
 * Lifecycle:
 *  - @BeforeAll  starts the browser, connects to the DB, init mail client
 *  - Each @Test  creates a fresh Orchestrator (clean message history)
 *  - @AfterAll   closes browser + DB connection
 *
 * Subclasses call run(goal) to execute a test scenario.
 *
 * Configuration via environment variables (set in your CI or .env file):
 *
 *   OLLAMA_BASE_URL     (default: http://localhost:11434)
 *   OLLAMA_MODEL        (default: qwen2.5:14b)
 *   BROWSER_HEADLESS    (default: false)
 *   DB_JDBC_URL
 *   DB_USERNAME
 *   DB_PASSWORD
 *   AZURE_TENANT_ID
 *   AZURE_CLIENT_ID
 *   AZURE_CLIENT_SECRET
 *   MAIL_USER_ID
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AgentTestBase {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected OllamaClient  ollamaClient;
    protected BrowserTool   browserTool;
    protected DatabaseTool  databaseTool;
    protected MailTool      mailTool;

    @BeforeAll
    void setUpFramework() throws Exception {
        log.info("=== Setting up agentic test framework ===");

        String ollamaUrl = env("OLLAMA_BASE_URL", "http://localhost:11434");
        String model     = env("OLLAMA_MODEL",     "qwen2.5:14b");
        boolean headless = Boolean.parseBoolean(env("BROWSER_HEADLESS", "false"));

        ollamaClient = new OllamaClient(ollamaUrl, model);
        browserTool  = new BrowserTool(headless);

        // DB — only init if env vars are present
        if (System.getenv("DB_JDBC_URL") != null) {
            databaseTool = DatabaseTool.fromEnv();
        } else {
            log.warn("DB_JDBC_URL not set — DatabaseTool disabled");
        }

        // Mail — only init if env vars are present
        if (System.getenv("AZURE_CLIENT_ID") != null) {
            mailTool = MailTool.fromEnv();
        } else {
            log.warn("AZURE_CLIENT_ID not set — MailTool disabled");
        }

        log.info("Framework ready. Model: {} | Headless: {}", model, headless);
    }

    @AfterAll
    void tearDownFramework() throws Exception {
        log.info("=== Tearing down agentic test framework ===");
        if (browserTool  != null) browserTool.close();
        if (databaseTool != null) databaseTool.close();
    }

    /**
     * Run a test goal through the agent loop.
     * Returns the final LLM response (should contain PASS or FAIL).
     */
    protected String run(String goal) throws Exception {
        return run(goal, null);
    }

    protected String run(String goal, String customSystemPrompt) throws Exception {
        Orchestrator orchestrator = new Orchestrator(ollamaClient)
                .register(browserTool);

        if (databaseTool != null) orchestrator.register(databaseTool);
        if (mailTool     != null) orchestrator.register(mailTool);

        String resolvedGoal = EnvTemplate.resolve(goal);
        log.info("Running goal: {}", resolvedGoal);
        String result = orchestrator.run(resolvedGoal, customSystemPrompt);
        log.info("Result: {}", result);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null && !val.isBlank() ? val : defaultValue;
    }
}
