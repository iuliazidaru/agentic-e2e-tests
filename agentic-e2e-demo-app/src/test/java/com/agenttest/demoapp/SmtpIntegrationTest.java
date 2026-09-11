package com.agenttest.demoapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.mail.enabled=true"
})
class SmtpIntegrationTest {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Test
    void smtpConnectionIsReachable() {
        JavaMailSenderImpl impl = (JavaMailSenderImpl) mailSender;
        assertDoesNotThrow(impl::testConnection,
            "Could not connect to SMTP server at " + impl.getHost() + ":" + impl.getPort());
    }

    @Test
    void sendTestEmail() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(fromAddress);
        message.setSubject("SMTP Integration Test");
        message.setText("This is a test email sent from the SmtpIntegrationTest to verify the SMTP configuration.");

        assertDoesNotThrow(() -> mailSender.send(message),
            "Failed to send test email via SMTP");
    }
}
