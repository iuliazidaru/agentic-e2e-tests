package com.agenttest.demoapp.service;

import com.agenttest.demoapp.model.Order;
import com.agenttest.demoapp.model.User;
import com.agenttest.demoapp.repository.OrderRepository;
import com.agenttest.demoapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MailService mailService;

    @Transactional
    public Order placeOrder(String username, Long productId, String emailAddress, String shippingAddress) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found: " + username));

        Order order = new Order();
        order.setUserId(user.getId());
        order.setProductId(productId);
        order.setStatus("CONFIRMED");
        order.setEmailAddress(emailAddress);
        order.setShippingAddress(shippingAddress);
        order.setCreatedAt(LocalDateTime.now());

        Order savedOrder = orderRepository.save(order);
        logger.info("Order #{} created for user {} with status CONFIRMED", savedOrder.getId(), username);

        // Send confirmation email to the address provided in the order
        mailService.sendOrderConfirmation(emailAddress, savedOrder.getId());

        return savedOrder;
    }

    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }
}
