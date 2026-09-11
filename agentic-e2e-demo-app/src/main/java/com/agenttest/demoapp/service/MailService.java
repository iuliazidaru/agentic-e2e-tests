package com.agenttest.demoapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private static final Logger logger = LoggerFactory.getLogger(MailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.mail.enabled:true}")
    private boolean mailEnabled;

    public void sendOrderConfirmation(String toEmail, Long orderId) {
        if (!mailEnabled) {
            logger.info("Mail sending is disabled. Would have sent order confirmation for order #{} to {}", orderId, toEmail);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(toEmail);
            message.setSubject("Order Confirmation #" + orderId);
            message.setText(buildOrderConfirmationBody(orderId));

            mailSender.send(message);
            logger.info("Order confirmation email sent to {} for order #{}", toEmail, orderId);
        } catch (Exception e) {
            logger.error("Failed to send order confirmation email for order #{}: {}", orderId, e.getMessage(), e);
        }
    }

    private String buildOrderConfirmationBody(Long orderId) {
        return String.format(
            "Thank you for your order!\n\n" +
            "Your order has been successfully placed.\n" +
            "Order Number: #%d\n" +
            "Status: CONFIRMED\n\n" +
            "We will process your order shortly.\n\n" +
            "Thank you for shopping with us!\n\n" +
            "Best regards,\n" +
            "The Demo App Team",
            orderId
        );
    }
}
