package com.hah.here.checkout;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;

    @GetMapping
    public CheckoutResponse getCheckout(
            @RequestParam @NotNull Long productId,
            @RequestParam @NotNull Long userId
    ) {
        return checkoutService.getCheckout(productId, userId);
    }
}
