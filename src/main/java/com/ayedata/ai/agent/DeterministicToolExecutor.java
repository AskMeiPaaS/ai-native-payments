package com.ayedata.ai.agent;

import com.ayedata.ai.tools.LedgerTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.internal.Json;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic tool execution engine — dispatches {@code @Tool} methods based on
 * a {@link PaSSOrchestratorAgent.ParsedIntent} without LLM involvement.
 *
 * <p>This class provides a fast, reliable, and fully auditable execution path:
 * <ul>
 *   <li>{@link #executeToolDirectly} — maps a classified intent to a registered
 *       {@link ToolExecutor} and invokes it via LangChain4j's pipeline</li>
 *   <li>{@link #buildQueryFilter} — builds a MongoDB filter JSON from user keywords
 *       (transaction type, payment method, time range) without any LLM call</li>
 *   <li>{@link #extractChannelFromResult} — parses "via &lt;CHANNEL&gt;" from tool
 *       result strings for UI feedback</li>
 * </ul>
 *
 * <p>All methods are deterministic — same input always produces the same output,
 * making them testable, auditable, and immune to LLM quality variance.
 *
 * @see PaSSOrchestratorAgent — the orchestrator that may delegate here
 * @see LedgerTools — the underlying {@code @Tool} methods
 */
public class DeterministicToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(DeterministicToolExecutor.class);

    private final LedgerTools ledgerTools;
    private final Map<String, ToolExecutor> toolExecutors;

    public DeterministicToolExecutor(LedgerTools ledgerTools, Map<String, ToolExecutor> toolExecutors) {
        this.ledgerTools = ledgerTools;
        this.toolExecutors = toolExecutors;
    }

    /**
     * Execute a LangChain4j {@code @Tool} via {@link ToolExecutor} based on the parsed intent.
     * Builds a {@link ToolExecutionRequest} with JSON arguments and delegates to the
     * registered {@link dev.langchain4j.service.tool.DefaultToolExecutor}, keeping all
     * tool invocations within LangChain4j's execution pipeline.
     *
     * @param sessionId   the current session ID (used as memory ID for tool execution)
     * @param intent      the classified intent from Stage 1
     * @param userIntent  the original user message (used for query filter building)
     * @return the tool result string, or {@code null} if the action is unrecognised
     */
    public String executeToolDirectly(String sessionId, PaSSOrchestratorAgent.ParsedIntent intent, String userIntent) {
        String toolName;
        Map<String, Object> args = new LinkedHashMap<>();

        switch (intent.action()) {
            case "TRANSFER" -> {
                toolName = "transferFunds";
                args.put("beneficiary", intent.beneficiary());
                if (!"UNKNOWN".equalsIgnoreCase(intent.channel())) {
                    args.put("targetBank", intent.channel());
                }
                args.put("amount", intent.amount());
            }
            case "RECEIVE" -> {
                toolName = "receiveFunds";
                args.put("amount", intent.amount());
                if (!"UNKNOWN".equalsIgnoreCase(intent.channel())) {
                    args.put("channel", intent.channel());
                }
            }
            case "MANDATE" -> {
                toolName = "switchMandate";
                args.put("bankName", intent.beneficiary());
                args.put("mandateDetails", "Mandate switch requested");
            }
            case "QUERY_BALANCE" -> {
                toolName = "checkBalance";
            }
            case "QUERY_TRANSACTIONS", "QUERY_SEARCH" -> {
                String searchTerm = intent.isQuerySearch() ? intent.beneficiary() : null;
                int limit = 10;
                String filterJson = buildQueryFilter(userIntent);
                log.info("Session {}: Executing MongoDB query: filter={} search='{}'",
                        sessionId, filterJson, searchTerm);
                String resolvedUserId = ledgerTools.resolveUserId(sessionId);
                return ledgerTools.executeMongoQuery(resolvedUserId, filterJson, searchTerm, limit);
            }
            default -> { return null; }
        }

        ToolExecutor executor = toolExecutors.get(toolName);
        if (executor == null) {
            log.error("No LangChain4j ToolExecutor found for tool: {}", toolName);
            return "TOOL_ERROR: Tool '" + toolName + "' is not registered.";
        }

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name(toolName)
                .arguments(Json.toJson(args))
                .build();

        log.info("Session {}: Executing via LangChain4j ToolExecutor: {}({})", sessionId, toolName, request.arguments());
        return executor.execute(request, sessionId);
    }

    /**
     * Build a MongoDB filter JSON string from the user's natural language query.
     * This is purely programmatic — no LLM involved — so it is deterministic and
     * cannot produce malformed output. The classifier only decides ACTION; this
     * method handles the "how to filter" part.
     *
     * <p>Detects three filter dimensions from keywords:
     * <ul>
     *   <li><b>Transaction type:</b> credit/receive/incoming → {@code PASS_MONEY_RECEIVE};
     *       debit/sent/paid/transfer → {@code PASS_MONEY_TRANSFER}</li>
     *   <li><b>Payment method:</b> UPI, UPI Lite, NEFT, RTGS, IMPS, Cheque</li>
     *   <li><b>Time range:</b> today (1d), yesterday (2d), this week (7d), this month (30d)</li>
     * </ul>
     *
     * @param userIntent the raw user message
     * @return a JSON filter string, e.g. {@code {"instructionType":"PASS_MONEY_TRANSFER","paymentMethod":"UPI"}}
     */
    public static String buildQueryFilter(String userIntent) {
        if (userIntent == null || userIntent.isBlank()) return "{}";
        String lower = userIntent.toLowerCase();

        // Detect transaction type keywords
        boolean wantsCredit = lower.matches(".*(\\bcredit|\\breceive|\\bincoming|\\binward).*");
        boolean wantsDebit  = lower.matches(".*(\\bdebit|\\bsent|\\bpaid|\\bpay\\b|\\boutgoing|\\boutward|\\btransfer).*");

        // If both or neither are mentioned → no type filter (show all with summary)
        String typeFilter = null;
        if (wantsCredit && !wantsDebit) {
            typeFilter = "PASS_MONEY_RECEIVE";
        } else if (wantsDebit && !wantsCredit) {
            typeFilter = "PASS_MONEY_TRANSFER";
        }

        // Detect payment method keywords
        String methodFilter = null;
        if (lower.contains("upi lite")) {
            methodFilter = "UPI LITE";
        } else if (lower.contains("upi")) {
            methodFilter = "UPI";
        } else if (lower.contains("neft")) {
            methodFilter = "NEFT";
        } else if (lower.contains("rtgs")) {
            methodFilter = "RTGS";
        } else if (lower.contains("imps")) {
            methodFilter = "IMPS";
        } else if (lower.contains("cheque") || lower.contains("check")) {
            methodFilter = "CHEQUE";
        }

        // Detect time range keywords
        String dateRange = null;
        if (lower.matches(".*(\\btoday\\b|\\blast 1 day\\b).*")) {
            dateRange = "1d";
        } else if (lower.matches(".*(\\byesterday\\b|\\blast 2 day).*")) {
            dateRange = "2d";
        } else if (lower.matches(".*(\\bthis week\\b|\\blast 7 day|\\bweek\\b).*")) {
            dateRange = "7d";
        } else if (lower.matches(".*(\\bthis month\\b|\\blast 30 day|\\bmonth\\b).*")) {
            dateRange = "30d";
        }

        // Build JSON filter
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        if (typeFilter != null) {
            json.append("\"instructionType\":\"").append(typeFilter).append("\"");
            first = false;
        }
        if (methodFilter != null) {
            if (!first) json.append(",");
            json.append("\"paymentMethod\":\"").append(methodFilter).append("\"");
            first = false;
        }
        if (dateRange != null) {
            if (!first) json.append(",");
            json.append("\"createdAt\":{\"$gte\":\"").append(dateRange).append("\"}");
        }
        json.append("}");
        return json.toString();
    }

    /**
     * Extract the payment channel from a tool result string like
     * {@code "SUCCESS: ₹100.00 transferred ... via UPI."}.
     *
     * @param result the tool execution result
     * @return the channel name (e.g. "UPI", "NEFT"), or {@code null} if not found
     */
    public static String extractChannelFromResult(String result) {
        if (result == null) return null;
        String upper = result.toUpperCase();
        // Check "via <channel>" pattern
        for (String ch : new String[]{"UPI LITE", "UPI", "NEFT", "RTGS", "IMPS", "CHEQUE"}) {
            if (upper.contains("VIA " + ch)) return ch;
        }
        return null;
    }
}
