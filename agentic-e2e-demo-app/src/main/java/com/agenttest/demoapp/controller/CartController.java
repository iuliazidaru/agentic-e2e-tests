package com.agenttest.demoapp.controller;

import com.agenttest.demoapp.model.Product;
import com.agenttest.demoapp.repository.ProductRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Controller
public class CartController {

    @Autowired
    private ProductRepository productRepository;

    @GetMapping("/checkout")
    public String checkout(HttpSession session, Model model) {
        @SuppressWarnings("unchecked")
        List<Long> cartProductIds = (List<Long>) session.getAttribute("cart");

        List<Product> cartProducts = new ArrayList<>();
        if (cartProductIds != null && !cartProductIds.isEmpty()) {
            for (Long productId : cartProductIds) {
                Optional<Product> productOpt = productRepository.findById(productId);
                productOpt.ifPresent(cartProducts::add);
            }
        }

        // If cart is empty, use the last added product or default to product 123
        Long selectedProductId = 123L;
        if (cartProductIds != null && !cartProductIds.isEmpty()) {
            selectedProductId = cartProductIds.get(cartProductIds.size() - 1);
        }

        model.addAttribute("cartProducts", cartProducts);
        model.addAttribute("selectedProductId", selectedProductId);
        model.addAttribute("cartSize", cartProducts.size());

        return "checkout";
    }
}
