package com.ayedata.controller;

import com.ayedata.service.AccountBalanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/account")
@CrossOrigin(origins = "${app.cors.allowed-origins:http://localhost:3000}", allowCredentials = "true")
public class AccountController {

    private final AccountBalanceService accountBalanceService;

    public AccountController(AccountBalanceService accountBalanceService) {
        this.accountBalanceService = accountBalanceService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard(@RequestParam(required = false) String userId) {
        return ResponseEntity.ok(accountBalanceService.getDashboard(userId));
    }

    public record TopupRequest(String userId, double amount, String method) {}

    @GetMapping("/reveal-pii")
    public ResponseEntity<Map<String, Object>> revealPii(
            @RequestParam(required = false) String userId,
            @RequestParam String field) {
        if (!"email".equalsIgnoreCase(field) && !"phone".equalsIgnoreCase(field)) {
            return ResponseEntity.badRequest().body(Map.of("error", "field must be 'email' or 'phone'"));
        }
        String value = accountBalanceService.revealPii(userId, field.toLowerCase());
        return ResponseEntity.ok(Map.of("value", value));
    }

    @PostMapping("/topup")
    public ResponseEntity<Map<String, Object>> topUp(@RequestBody TopupRequest request) {
        if (request.amount() <= 0 || request.amount() > 10_00_000.00) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Amount must be between ₹1 and ₹10,00,000"));
        }
        String resolvedUserId = accountBalanceService.normalizeUserId(request.userId());
        accountBalanceService.creditBalance(resolvedUserId, request.amount());
        return ResponseEntity.ok(accountBalanceService.getDashboard(resolvedUserId));
    }
}
