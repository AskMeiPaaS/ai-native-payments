package com.ayedata.ai.agent;

import com.ayedata.rag.service.RagService;
import com.ayedata.service.TemporalMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds enriched user intent by prepending RAG knowledge and temporal memory
 * context before the original user message. Used by the orchestrator to give
 * the Supervisor Agent richer context for each request.
 */
@Component
public class ContextEnricher {
    private static final Logger log = LoggerFactory.getLogger(ContextEnricher.class);

    /** Max chars for agent reply text within each recalled temporal turn. */
    private static final int MAX_TEMPORAL_REPLY_CHARS = 150;
    /** Max chars for user text within each recalled temporal turn. */
    private static final int MAX_TEMPORAL_USER_CHARS = 100;

    /**
     * Canonical channel names paired with the uppercase keywords used to detect
     * them in RAG text. UPI Lite MUST appear before UPI (subset match guard).
     * Format: {canonicalName, keyword1, keyword2, ...}
     */
    private static final String[][] CHANNEL_SYNONYMS = {
        {"UPI Lite",  "UPI LITE",  "UPILITE"},
        {"UPI",       "UPI",        "UNIFIED PAYMENTS INTERFACE", "UNIFIED PAYMENT INTERFACE"},
        {"NEFT",      "NEFT",       "NATIONAL ELECTRONIC FUNDS TRANSFER"},
        {"RTGS",      "RTGS",       "REAL TIME GROSS SETTLEMENT", "REAL-TIME GROSS SETTLEMENT"},
        {"IMPS",      "IMPS",       "IMMEDIATE PAYMENT SERVICE"},
        {"Cheque",    "CHEQUE"},
        {"Cash",      "CASH"},
    };

    private final RagService ragService;
    private final TemporalMemoryService temporalMemoryService;

    public ContextEnricher(RagService ragService, TemporalMemoryService temporalMemoryService) {
        this.ragService = ragService;
        this.temporalMemoryService = temporalMemoryService;
    }

    /**
     * Build an enriched user message by prepending:
     * <ol>
     *   <li>RAG knowledge chunks relevant to the user intent</li>
     *   <li>Approved channels derived from those RAG chunks</li>
     *   <li>Semantically similar past turns from temporal memory</li>
     * </ol>
     * Falls back to the original intent string if both sources return nothing.
     */
    public String buildEnrichedIntent(String sessionId, String userIntent) {
        StringBuilder sb = new StringBuilder();
        int ragChars = 0;
        int temporalTurns = 0;
        List<String> approvedChannels = List.of();

        // 1. RAG — domain knowledge relevant to this query (budget managed by RagService)
        try {
            String ragContext = ragService.retrieveContext(userIntent, 2);
            if (!ragContext.isBlank()) {
                sb.append("[RELEVANT KNOWLEDGE]\n").append(ragContext).append("\n\n");
                ragChars = ragContext.length();

                // 2. Derive approved channels solely from what RAG returned
                approvedChannels = extractApprovedChannels(ragContext);
                if (!approvedChannels.isEmpty()) {
                    sb.append("[APPROVED CHANNELS]\n");
                    sb.append("You MUST choose the payment channel exclusively from this list "
                            + "(derived from the retrieved knowledge above): ");
                    sb.append(String.join(", ", approvedChannels)).append("\n\n");
                    log.info("Session {}: approved channels from RAG = {}", sessionId, approvedChannels);
                }
            }
        } catch (Exception e) {
            log.debug("Session {}: RAG context unavailable: {}", sessionId, e.getMessage());
        }

        // 3. Temporal memory — compacted past turns
        try {
            List<Map<String, String>> history = temporalMemoryService.recallRelevantHistory(sessionId, userIntent, 2);
            if (!history.isEmpty()) {
                sb.append("[CONVERSATION HISTORY]\n");
                for (Map<String, String> turn : history) {
                    sb.append("User: ").append(truncate(turn.get("userText"), MAX_TEMPORAL_USER_CHARS)).append("\n");
                    sb.append("Agent: ").append(truncate(turn.get("aiText"), MAX_TEMPORAL_REPLY_CHARS)).append("\n\n");
                }
                temporalTurns = history.size();
            }
        } catch (Exception e) {
            log.debug("Session {}: Temporal recall unavailable: {}", sessionId, e.getMessage());
        }

        // 4. Current request
        if (sb.length() > 0) {
            sb.append("[CURRENT REQUEST]\n");
        }
        sb.append(userIntent);

        String enriched = sb.toString();
        log.info("Session {}: enriched context = {} chars (RAG={}, channels={}, temporal={}turns, intent={})",
                sessionId, enriched.length(), ragChars, approvedChannels.size(), temporalTurns, userIntent.length());
        return enriched;
    }

    /**
     * Scan the RAG-retrieved text and return the canonical names of payment channels
     * that appear in it. Only channels present in the knowledge base are returned;
     * the LLM must not invent channels that are not listed here.
     */
    static List<String> extractApprovedChannels(String ragContext) {
        if (ragContext == null || ragContext.isBlank()) return List.of();
        String upper = ragContext.toUpperCase();
        List<String> approved = new ArrayList<>();
        for (String[] entry : CHANNEL_SYNONYMS) {
            String canonical = entry[0];
            for (int i = 1; i < entry.length; i++) {
                if (upper.contains(entry[i])) {
                    approved.add(canonical);
                    break; // found this channel; move on to next
                }
            }
        }
        return approved;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }
}
