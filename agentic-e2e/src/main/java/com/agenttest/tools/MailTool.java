package com.agenttest.tools;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft Graph-backed mail tool.
 *
 * Supported actions:
 *
 *   sendMail        — send an email from the configured mailbox
 *   listMessages    — list recent messages (subject + sender + id)
 *   readMessage     — read body of a specific message by id
 *   waitForMessage  — poll inbox until a matching subject arrives (with timeout)
 *
 * Auth: Client Credentials flow (app registration with Mail.Send + Mail.Read).
 * Set env vars or pass via MailConfig:
 *   AZURE_TENANT_ID, AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, MAIL_USER_ID
 */
public class MailTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(MailTool.class);

    private final GraphServiceClient graphClient;
    private final String             mailboxUserId;
    private final ObjectMapper       mapper = new ObjectMapper();

    public MailTool(String tenantId, String clientId, String clientSecret, String mailboxUserId) {
        this.mailboxUserId = mailboxUserId;

        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .tenantId(tenantId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();

        this.graphClient = new GraphServiceClient(credential, "https://graph.microsoft.com/.default");

        log.info("MailTool initialised for mailbox: {}", mailboxUserId);
    }

    /** Convenience constructor that reads from environment variables (or system properties loaded from .env). */
    public static MailTool fromEnv() {
        return new MailTool(
                envOrProp("AZURE_TENANT_ID"),
                envOrProp("AZURE_CLIENT_ID"),
                envOrProp("AZURE_CLIENT_SECRET"),
                envOrProp("MAIL_USER_ID")
        );
    }

    private static String envOrProp(String key) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : System.getProperty(key);
    }

    @Override public String name()        { return "mail"; }
    @Override public String description() {
        return "Sends and reads Outlook emails via Microsoft Graph. " +
               "Actions: sendMail, listMessages, readMessage, waitForMessage.";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("action").put("type", "string")
             .put("description", "sendMail | listMessages | readMessage | waitForMessage");
        
        props.putObject("to").put("type", "string")
             .put("description", "Recipient email address (sendMail)");
        props.putObject("subject").put("type", "string")
             .put("description", "Email subject");
        props.putObject("body").put("type", "string")
             .put("description", "Email body text (sendMail)");
        props.putObject("messageId").put("type", "string")
             .put("description", "Graph message id (readMessage)");
        props.putObject("subjectContains").put("type", "string")
             .put("description", "Subject substring to match (waitForMessage)");
        props.putObject("timeoutSeconds").put("type", "integer")
             .put("description", "Poll timeout in seconds for waitForMessage (default 120)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String action = args.path("action").asText();
        return switch (action) {
            case "sendMail"       -> sendMail(args);
            case "listMessages"   -> listMessages(args);
            case "readMessage"    -> readMessage(args);
            case "waitForMessage" -> waitForMessage(args);
            default -> "ERROR: Unknown mail action '" + action + "'";
        };
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private String sendMail(JsonNode args) {
        String to       = args.path("to").asText();
        String subject  = args.path("subject").asText();
        String bodyText = args.path("body").asText();

        Message message = new Message();
        message.setSubject(subject);

        ItemBody itemBody = new ItemBody();
        itemBody.setContentType(BodyType.Text);
        itemBody.setContent(bodyText);
        message.setBody(itemBody);

        Recipient recipient = new Recipient();
        EmailAddress emailAddress = new EmailAddress();
        emailAddress.setAddress(to);
        recipient.setEmailAddress(emailAddress);
        message.setToRecipients(Collections.singletonList(recipient));

        SendMailPostRequestBody requestBody = new SendMailPostRequestBody();
        requestBody.setMessage(message);
        requestBody.setSaveToSentItems(true);

        graphClient.users().byUserId(mailboxUserId).sendMail().post(requestBody);
        log.info("Mail sent to {} subject='{}'", to, subject);
        return "Email sent successfully to " + to + " with subject: " + subject;
    }

    private String listMessages(JsonNode args) {
        int top = args.path("top").asInt(10);

        MessageCollectionResponse messages = graphClient.users().byUserId(mailboxUserId)
                .messages()
                .get(requestConfig -> {
                    requestConfig.queryParameters.select =
                            new String[]{"id", "subject", "from", "receivedDateTime", "isRead"};
                    requestConfig.queryParameters.top = top;
                    requestConfig.queryParameters.orderby = new String[]{"receivedDateTime desc"};
                });

        if (messages == null || messages.getValue() == null || messages.getValue().isEmpty()) {
            return "Inbox is empty.";
        }

        StringBuilder sb = new StringBuilder("Recent messages:\n");
        for (Message msg : messages.getValue()) {
            String from = (msg.getFrom() != null && msg.getFrom().getEmailAddress() != null)
                    ? msg.getFrom().getEmailAddress().getAddress() : "?";
            sb.append(String.format("  id=%s | subject='%s' | from=%s | received=%s\n",
                    msg.getId(), msg.getSubject(), from, msg.getReceivedDateTime()));
        }
        return sb.toString();
    }

    private String readMessage(JsonNode args) {
        String messageId = args.path("messageId").asText();
        Message msg = graphClient.users().byUserId(mailboxUserId)
                .messages().byMessageId(messageId)
                .get(requestConfig -> requestConfig.queryParameters.select =
                        new String[]{"subject", "from", "body", "receivedDateTime"});

        if (msg == null) return "Message not found: " + messageId;

        String from = (msg.getFrom() != null && msg.getFrom().getEmailAddress() != null)
                ? msg.getFrom().getEmailAddress().getAddress() : "?";

        return String.format("Subject: %s\nFrom: %s\nReceived: %s\nBody:\n%s",
                msg.getSubject(),
                from,
                msg.getReceivedDateTime(),
                msg.getBody() != null ? stripHtml(msg.getBody().getContent()) : "(no body)");
    }

    private String waitForMessage(JsonNode args) throws InterruptedException {
        String subjectContains = args.path("subjectContains").asText();
        int    timeoutSeconds  = args.path("timeoutSeconds").asInt(120);
        long   deadline        = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int    pollIntervalSec = 5;
        String filterExpr      = "contains(subject,'" + subjectContains.replace("'", "''") + "')";

        log.info("Waiting up to {}s for email with subject containing '{}'",
                timeoutSeconds, subjectContains);

        while (System.currentTimeMillis() < deadline) {
            MessageCollectionResponse messages = graphClient.users().byUserId(mailboxUserId)
                    .messages()
                    .get(requestConfig -> {
                        requestConfig.queryParameters.select =
                                new String[]{"id", "subject", "from", "receivedDateTime"};
                        requestConfig.queryParameters.top = 20;
                        requestConfig.queryParameters.orderby = new String[]{"receivedDateTime desc"};
                        requestConfig.queryParameters.filter = filterExpr;
                    });

            if (messages != null && messages.getValue() != null && !messages.getValue().isEmpty()) {
                Message found = messages.getValue().get(0);
                log.info("Found email: subject='{}' id={}", found.getSubject(), found.getId());
                return "Email received! id=" + found.getId() +
                       " | subject='" + found.getSubject() + "'" +
                       " | received=" + found.getReceivedDateTime();
            }

            log.debug("Not found yet, retrying in {}s...", pollIntervalSec);
            TimeUnit.SECONDS.sleep(pollIntervalSec);
        }

        return "TIMEOUT: Email with subject containing '" + subjectContains +
               "' did not arrive within " + timeoutSeconds + " seconds.";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
    }
}
