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
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        loadDotEnv();

        String ollamaUrl = env("OLLAMA_BASE_URL", "http://localhost:11434");
        String model     = env("OLLAMA_MODEL",     "qwen2.5:14b");
        boolean headless = Boolean.parseBoolean(env("BROWSER_HEADLESS", "false"));

        ollamaClient = new OllamaClient(ollamaUrl, model);
        browserTool  = new BrowserTool(headless);

        // DB — only init if env vars are present
        if (env("DB_JDBC_URL", null) != null) {
            databaseTool = DatabaseTool.fromEnv();
        } else {
            log.warn("DB_JDBC_URL not set — DatabaseTool disabled");
        }

        // Mail — only init if env vars are present
        if (env("AZURE_CLIENT_ID", null) != null) {
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

    /**
     * Loads key=value pairs from a .env file (project root or module root) into
     * system properties so EnvTemplate.resolve() and System.getenv() wrappers
     * can pick them up. Existing environment variables always take precedence.
     * Lines starting with # and blank lines are ignored.
     */
    private void loadDotEnv() {
        // Look for .env alongside pom.xml — try module dir first, then project root.
        Path[] candidates = {
            Paths.get(".env"),
            Paths.get("agentic-e2e/.env"),
            Paths.get("../.env"),
        };
        for (Path candidate : candidates) {
            if (candidate.toFile().exists()) {
                log.info("Loading .env from {}", candidate.toAbsolutePath());
                try (BufferedReader reader = new BufferedReader(new FileReader(candidate.toFile()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.strip();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int eq = line.indexOf('=');
                        if (eq <= 0) continue;
                        String key   = line.substring(0, eq).strip();
                        String value = line.substring(eq + 1).strip()
                                           .replaceAll("^['\"]|['\"]$", ""); // strip surrounding quotes
                        // Env vars set in the OS always win over .env
                        if (System.getenv(key) == null) {
                            System.setProperty(key, value);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not read .env file at {}: {}", candidate, e.getMessage());
                }
                return; // stop at first .env found
            }
        }
        log.debug("No .env file found — relying on environment variables only.");
    }

    /** Skip the current test if the database tool was not configured. */
    protected void requireDatabase() {
        Assumptions.assumeTrue(databaseTool != null,
                "Skipping: DB_JDBC_URL not set — DatabaseTool not available");
    }

    /** Skip the current test if the mail tool was not configured. */
    protected void requireMail() {
        Assumptions.assumeTrue(mailTool != null,
                "Skipping: AZURE_CLIENT_ID not set — MailTool not available");
    }

    /** Reads a variable from OS environment first, then system properties (populated from .env). */
    private String env(String key, String defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) val = System.getProperty(key);
        return val != null && !val.isBlank() ? val : defaultValue;
    }
}
