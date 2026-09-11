# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

### Infrastructure (required before running the app)
```bash
podman-compose up -d        # Start PostgreSQL (port 5432) and MailHog (SMTP: 1025, Web UI: 8025)
podman-compose down         # Stop infrastructure
```

### Run & Build
```bash
mvn spring-boot:run         # Start the application on port 8080
mvn clean package           # Build JAR
mvn test                    # Run all tests
mvn test -Dtest=ClassName   # Run a single test class
```

### Test credentials
- `admin` / `admin123`
- `testuser` / `test123`

## Architecture

Spring Boot 3.2 monolith with server-side Thymeleaf rendering and PostgreSQL.

**Layered MVC:**
- `controller/` — HTTP layer: Auth, Product, Cart, Order controllers
- `service/` — Business logic: OrderService (places orders + triggers email), MailService
- `repository/` — Spring Data JPA repositories for User, Product, Order
- `model/` — JPA entities
- `resources/templates/` — Thymeleaf HTML views
- `resources/db/migration/` — Flyway migrations (schema + seed data in V1__init.sql)
- `config/` — SecurityConfig (form login, BCrypt) and MailConfig

**Shopping cart** is stored in `HttpSession` (not the database) — it lives only for the duration of the user's session.

**User flow:** login → /products → add to cart (session) → /checkout → /order/place (persists Order, sends email) → /order/confirmation/{id}

**Email** uses MailHog locally (no real SMTP needed). View sent emails at http://localhost:8025.

## MailHog (local email testing)

MailHog intercepts all outgoing emails so no real SMTP server is needed during local development.

**Web UI:** http://localhost:8025

After placing an order, open the Web UI to see the confirmation email. All emails sent by the app appear there regardless of the recipient address.

To switch back to MailHog after using a real SMTP server, restore these settings in `application.properties`:
```properties
spring.mail.host=localhost
spring.mail.port=1025
spring.mail.username=
spring.mail.password=
spring.mail.properties.mail.smtp.auth=false
spring.mail.properties.mail.smtp.starttls.enable=false

app.mail.from=noreply@myapp.local
```

## Testing

Tests use H2 in-memory database with Flyway disabled (`create-drop` DDL). Test config is applied via `@TestPropertySource` in `DemoAppApplicationTests`. Only a context-load smoke test exists currently.

`SmtpIntegrationTest` verifies the configured SMTP server is reachable and can send email. It uses the credentials from `application.properties` and is intended to be run manually when changing mail configuration:
```bash
mvn test -Dtest=SmtpIntegrationTest
```
