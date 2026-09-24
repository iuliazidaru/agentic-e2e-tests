package com.agenttest.tools;

import com.agenttest.agent.Orchestrator.HumanInputRequiredException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Base64;

/**
 * Playwright-backed browser tool.
 *
 * Supported actions (passed as "action" argument to the LLM):
 *
 *   navigate      — go to a URL
 *   click         — click a CSS selector
 *   fill          — type text into a field
 *   select        — choose an option in a <select>
 *   getText       — return visible text of an element
 *   getPageText   — return full page text content
 *   screenshot    — take a screenshot, return base64
 *   waitForLogin  — PAUSES for human to complete custom login
 *   waitForSelector — wait until element appears
 *
 * One Playwright browser instance is shared across the whole test run.
 * Call close() in @AfterAll.
 */
public class BrowserTool implements AgentTool, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);

    private final Playwright playwright;
    private final Browser     browser;
    private final Page        page;
    private final ObjectMapper mapper = new ObjectMapper();

    public BrowserTool() {
        this(false); // headless = false so you can see the browser
    }

    public BrowserTool(boolean headless) {
        playwright = Playwright.create();
        browser    = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless));
        BrowserContext ctx = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(1280, 900)
                        .setIgnoreHTTPSErrors(true));
        page = ctx.newPage();
        log.info("Browser started (headless={})", headless);
    }

    @Override public String name()        { return "browser"; }
    @Override public String description() {
        return "Controls a Chromium browser. Use actions: navigate, click, fill, select, " +
               "getText, getPageText, screenshot, waitForLogin, waitForSelector.";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("action")
             .put("type", "string")
             .put("description", "Action to perform: navigate|click|fill|select|getText|getPageText|screenshot|waitForLogin|waitForSelector");
        props.putObject("url")
             .put("type", "string")
             .put("description", "URL for navigate action");
        props.putObject("selector")
             .put("type", "string")
             .put("description", "CSS selector for element-based actions");
        props.putObject("value")
             .put("type", "string")
             .put("description", "Text value for fill/select actions");
        props.putObject("timeout")
             .put("type", "integer")
             .put("description", "Timeout in ms (default 30000)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String action  = args.path("action").asText();
        String selector = args.path("selector").asText(null);
        String value    = args.path("value").asText(null);
        int    timeout  = args.path("timeout").asInt(30_000);

        return switch (action) {
            case "navigate" -> {
                String url = args.path("url").asText("");
                if (url.isBlank()) {
                    yield "ERROR: 'url' argument is required for navigate action";
                }
                log.info("navigate → {}", url);
                page.navigate(url);
                page.waitForLoadState();
                yield "Navigated to " + url + ". Title: " + page.title();
            }
            case "click" -> {
                log.info("click → {}", selector);
                page.click(selector, new Page.ClickOptions().setTimeout(timeout));
                yield "Clicked " + selector;
            }
            case "fill" -> {
                log.info("fill → {} = {}", selector, value);
                page.fill(selector, value, new Page.FillOptions().setTimeout(timeout));
                yield "Filled " + selector + " with value";
            }
            case "select" -> {
                log.info("select → {} = {}", selector, value);
                page.selectOption(selector, value);
                yield "Selected '" + value + "' in " + selector;
            }
            case "getText" -> {
                String text = page.textContent(selector,
                        new Page.TextContentOptions().setTimeout(timeout));
                yield text != null ? text.trim() : "(empty)";
            }
            case "getPageText" -> {
                yield page.innerText("body").substring(0, Math.min(4000,
                        page.innerText("body").length()));
            }
            case "screenshot" -> {
                byte[] bytes = page.screenshot(
                        new Page.ScreenshotOptions().setFullPage(false));
                String b64 = Base64.getEncoder().encodeToString(bytes);
                yield "Screenshot captured (base64 length=" + b64.length() + ")";
            }
            case "waitForSelector" -> {
                page.waitForSelector(selector,
                        new Page.WaitForSelectorOptions().setTimeout(timeout));
                yield "Element found: " + selector;
            }
            case "waitForLogin" -> {
                String loginUrl = args.path("url").asText(null);
                if (loginUrl != null) {
                    log.info("waitForLogin → navigating to {}", loginUrl);
                    page.navigate(loginUrl);
                    page.waitForLoadState();
                }
                // This suspends the agent loop via the Orchestrator's human-pause handler
                throw new HumanInputRequiredException(
                        "Please complete login in the browser window.\n" +
                        "Current URL: " + page.url() + "\n" +
                        "After logging in successfully, press Enter to continue.");
            }
            default -> "ERROR: Unknown action '" + action + "'";
        };
    }

    /** Direct page access for assertions in tests. */
    public Page page() { return page; }

    @Override
    public void close() {
        try { browser.close(); }    catch (Exception ignored) {}
        try { playwright.close(); } catch (Exception ignored) {}
        log.info("Browser closed");
    }
}
