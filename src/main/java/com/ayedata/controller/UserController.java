package com.ayedata.controller;

import com.ayedata.domain.UserProfile;
import com.ayedata.init.UserProfileInitializer;
import com.ayedata.service.AccountBalanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles user authentication (userId-only, no password) and profile listing
 * for the demo application.
 */
@RestController
@RequestMapping("/api/v1/users")
@CrossOrigin(origins = "${app.cors.allowed-origins:http://localhost:3000}", allowCredentials = "true")
public class UserController {

    private final AccountBalanceService accountBalanceService;

    public UserController(AccountBalanceService accountBalanceService) {
        this.accountBalanceService = accountBalanceService;
    }

    /**
     * Returns the list of available demo user IDs for display on the login screen.
     * Only exposes non-sensitive identifiers (userId, displayName).
     */
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, String>>> listUsers() {
        List<Map<String, String>> users = UserProfileInitializer.getDemoUserIds().stream()
                .map(userId -> {
                    UserProfile profile = accountBalanceService.getOrCreateProfile(userId);
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("userId", profile.getId());
                    entry.put("displayName", profile.getDisplayName());
                    return entry;
                })
                .toList();
        return ResponseEntity.ok(users);
    }

    /**
     * Validates that a userId exists and returns minimal profile info.
     * No password required — this is a demo-only login mechanism.
     *
     * @param userId the user identifier entered on the login screen
     * @return 200 with profile summary, or 404 if the userId is not recognized
     */
    @GetMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestParam String userId) {
        String normalized = accountBalanceService.normalizeUserId(userId);
        UserProfile profile = accountBalanceService.getOrCreateProfile(normalized);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", profile.getId());
        response.put("displayName", profile.getDisplayName());
        response.put("email", profile.getEmail());
        response.put("phone", profile.getPhone());
        response.put("currency", profile.getCurrency());
        response.put("currentBalance", profile.getCurrentBalance());
        return ResponseEntity.ok(response);
    }
}
