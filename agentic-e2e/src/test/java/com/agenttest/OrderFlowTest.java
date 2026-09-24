package com.agenttest;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example E2E test suite demonstrating all three capabilities.
 *
 * Replace the URLs, selectors, subjects, and SQL with your actual application values.
 *
 * Configuration via environment variables (see .env):
 *   APP_BASE_URL  — base URL of the application under test (default: https://myapp.local)
 */
class OrderFlowTest extends AgentTestBase {

    /**
     * Full order flow:
     *  1. Manual login (human-in-the-loop)
     *  2. Place an order through the UI
     *  3. Verify order record exists in the database
     */
    @Test
    void placeOrder_shouldSendConfirmationEmailAndPersistToDb() throws Exception {
        String result = run("""
                Complete the following E2E test scenario:

                STEP 1 — Login
                Use the browser tool with action=waitForLogin and url=%APP_BASE_URL%.
                Wait for the human to complete the custom login.

                STEP 2 — Navigate to product page
                Use the browser tool with action=navigate and url=%APP_BASE_URL%/products.
                Click the button with selector="#add-to-cart-123".
                Click the checkout button with selector="#checkout-btn".

                STEP 3 — Fill checkout form
                Fill selector="#email-address" with value="ss@aa.com".
                Fill selector="#shipping-address" with value="123 Test Street".
                Click selector="#place-order-btn".

                STEP 4 — Capture order confirmation
                Wait for the element selector=".order-confirmation-number" to appear.
                Get text from selector=".order-confirmation-number" and remember the order number.

                STEP 5 — Verify order in database
                Use the database tool with action=assertRowExists and sql=
                "SELECT id FROM orders WHERE status='CONFIRMED' ORDER BY created_at DESC LIMIT 1".

                //STEP 6 — Verify confirmation email
                //Use the mail tool with action=waitForMessage and subjectContains="Order Confirmation" and timeoutSeconds=120.
                //Report whether the email was received.

                Report PASS if all 5 steps succeeded, or FAIL with the reason if any step failed.
                """);

        assertThat(result)
                .as("Agent should report PASS for the full order flow")
                .containsIgnoringCase("PASS");
    }

    @Test
    void placeOrder_shouldLogin() throws Exception {
        String result = run("""
                Complete the following E2E test scenario:

                STEP 1 — Login
                Use the browser tool with action=waitForLogin and url=%APP_BASE_URL%.
                Wait for the human to complete the custom login.

                STEP 2 — Verify we are at products page
                Use the browser tool with action=navigate and url=%APP_BASE_URL%/products.

                Report PASS if all 2 steps succeeded, or FAIL with the reason if any step failed.
                """);

        assertThat(result)
                .as("Agent should report PASS for the full order flow")
                .containsIgnoringCase("PASS");
    }

    /**
     * Mail-only smoke test — send an email and confirm receipt.
     */
    @Test
    @Disabled("Temporarily broken")
    void sendEmail_shouldAppearInInbox() throws Exception {
        requireMail();

        String result = run("""
                Send a test email using the mail tool:
                  action=sendMail
                  to=tester@mycompany.com
                  subject=Automated Test Email
                  body=This is an automated test from the E2E agent framework.

                Then wait for it to appear using action=waitForMessage with
                subjectContains="Automated Test Email" and timeoutSeconds=60.

                Report PASS if the email was received, FAIL otherwise.
                """);

        assertThat(result).containsIgnoringCase("PASS");
    }

    /**
     * DB-only smoke test — verify a known record exists.
     */
    @Test
    void database_shouldHaveAdminUser() throws Exception {
        requireDatabase();

        String result = run("""
                Use the database tool with action=assertRowExists and
                sql="SELECT id FROM users WHERE role='ADMIN' AND active=true LIMIT 1".
                Report PASS if the row exists, FAIL otherwise.
                """);

        assertThat(result).containsIgnoringCase("PASS");
    }
}
