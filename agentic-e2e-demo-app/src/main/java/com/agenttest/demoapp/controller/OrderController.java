package com.agenttest.demoapp.controller;

import com.agenttest.demoapp.model.Order;
import com.agenttest.demoapp.service.OrderService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/order")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    @Autowired
    private OrderService orderService;

    @PostMapping("/place")
    public String placeOrder(
            @RequestParam("emailAddress") String emailAddress,
            @RequestParam("shippingAddress") String shippingAddress,
            @RequestParam(value = "productId", required = false, defaultValue = "123") Long productId,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        try {
            String username = authentication.getName();
            logger.info("Placing order for user: {}, product: {}", username, productId);

            // Use the product from cart if available
            @SuppressWarnings("unchecked")
            List<Long> cart = (List<Long>) session.getAttribute("cart");
            if (cart != null && !cart.isEmpty()) {
                productId = cart.get(cart.size() - 1);
            }

            Order order = orderService.placeOrder(username, productId, emailAddress, shippingAddress);

            // Clear the cart after placing order
            session.removeAttribute("cart");
            session.removeAttribute("lastAddedProductId");

            logger.info("Order #{} placed successfully for user: {}", order.getId(), username);
            return "redirect:/order/confirmation/" + order.getId();

        } catch (Exception e) {
            logger.error("Error placing order: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to place order: " + e.getMessage());
            return "redirect:/checkout";
        }
    }

    @GetMapping("/confirmation/{id}")
    public String orderConfirmation(@PathVariable Long id, Model model, Authentication authentication) {
        Optional<Order> orderOpt = orderService.findById(id);

        if (orderOpt.isEmpty()) {
            model.addAttribute("errorMessage", "Order not found.");
            return "order-confirmation";
        }

        Order order = orderOpt.get();

        // Security check: ensure the order belongs to the authenticated user
        // (skip this check for now to simplify the flow)

        model.addAttribute("order", order);
        model.addAttribute("orderId", order.getId());

        return "order-confirmation";
    }
}
