package com.agenttest.demoapp.controller;

import com.agenttest.demoapp.model.Product;
import com.agenttest.demoapp.repository.ProductRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Controller
public class ProductController {

    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);

    @Autowired
    private ProductRepository productRepository;

    @GetMapping("/products")
    public String listProducts(Model model, HttpSession session) {
        List<Product> products = productRepository.findAll();
        model.addAttribute("products", products);

        @SuppressWarnings("unchecked")
        List<Long> cart = (List<Long>) session.getAttribute("cart");
        int cartSize = (cart != null) ? cart.size() : 0;
        model.addAttribute("cartSize", cartSize);

        return "products";
    }

    @PostMapping("/cart/add")
    public String addToCart(@RequestParam("productId") Long productId,
                            HttpSession session,
                            RedirectAttributes redirectAttributes) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Product not found.");
            return "redirect:/products";
        }

        @SuppressWarnings("unchecked")
        List<Long> cart = (List<Long>) session.getAttribute("cart");
        if (cart == null) {
            cart = new ArrayList<>();
        }
        cart.add(productId);
        session.setAttribute("cart", cart);
        session.setAttribute("lastAddedProductId", productId);

        logger.info("Added product {} to cart. Cart size: {}", productId, cart.size());
        redirectAttributes.addFlashAttribute("successMessage", "Product added to cart!");

        return "redirect:/products";
    }
}
