package com.ayedata.rag.init;

import com.ayedata.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Seeds the RAG knowledge base with payment domain documents.
 * Uses upsert-by-id — safe to re-run; new documents are added, existing ones updated.
 */
@Component
public class RagKnowledgeSeeder {

    private static final Logger log = LoggerFactory.getLogger(RagKnowledgeSeeder.class);

    private final RagService ragService;

    /** Each entry: [document-id, title, classpath-resource-path]. */
    private static final List<String[]> DOCUMENTS = List.of(
        new String[]{"upi-mandate-overview",
            "UPI Mandate & Recurring Payments Overview",
            null},
        new String[]{"nach-mandate-switching",
            "NACH Mandate Switching Process",
            null},
        new String[]{"fraud-detection-policy",
            "PaSS Fraud Detection & Behavioral Analysis Policy",
            null},
        new String[]{"hitl-escalation-policy",
            "Human-In-The-Loop (HITL) Escalation Policy",
            null},
        new String[]{"pass-orchestrator-guide",
            "PaSS Orchestrator Agent Decision Guide",
            null},
        // ── Indian Payment Channel RAG documents ──
        new String[]{"upi-lite-payment-channel",
            "UPI Lite Payment Channel — Risk & Routing Guide",
            "rag/upi-lite-payment-channel.txt"},
        new String[]{"upi-payment-channel",
            "UPI Payment Channel — Risk & Routing Guide",
            "rag/upi-payment-channel.txt"},
        new String[]{"neft-payment-channel",
            "NEFT Payment Channel — Risk & Routing Guide",
            "rag/neft-payment-channel.txt"},
        new String[]{"rtgs-payment-channel",
            "RTGS Payment Channel — Risk & Routing Guide",
            "rag/rtgs-payment-channel.txt"},
        new String[]{"cheque-payment-channel",
            "Cheque Payment Channel — Risk & Routing Guide",
            "rag/cheque-payment-channel.txt"},
        new String[]{"payment-routing-risk-matrix",
            "Indian Payment Routing Decision Matrix",
            "rag/payment-routing-risk-matrix.txt"},
        new String[]{"indian-payment-regulatory-framework",
            "Indian Payment Systems Regulatory Framework",
            "rag/indian-payment-regulatory-framework.txt"}
    );

    /** Expected total after seeding — used for the skip-if-complete check. */
    private static final int EXPECTED_DOC_COUNT = DOCUMENTS.size();

    public RagKnowledgeSeeder(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Seed PaSS domain knowledge into the RAG collection.
     * Idempotent — skips only when ALL expected documents are already present.
     */
    public void seed() {
        try {
            long existing = ragService.documentCount();
            if (existing >= EXPECTED_DOC_COUNT) {
                log.info("✅ RAG knowledge base already has {} docs (expected {}) — skipping.",
                         existing, EXPECTED_DOC_COUNT);
                return;
            }
            log.info("📚 Seeding RAG knowledge base ({} existing, {} expected)...",
                     existing, EXPECTED_DOC_COUNT);

            int seeded = 0;
            for (String[] doc : DOCUMENTS) {
                String id = doc[0];
                String title = doc[1];
                String resourcePath = doc[2];

                String content = (resourcePath != null)
                    ? loadResource(resourcePath)
                    : loadInlineContent(id);

                if (content != null) {
                    ragService.ingestDocument(id, title, content);
                    seeded++;
                }
            }
            log.info("✅ RAG knowledge base seeded with {} documents (total now {}).",
                     seeded, EXPECTED_DOC_COUNT);
        } catch (Exception e) {
            log.warn("⚠️ RAG seeding failed (will retry on next startup): {}", e.getMessage());
        }
    }

    private String loadResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("⚠️ Could not load RAG resource {}: {}", path, e.getMessage());
            return null;
        }
    }

    /** Inline content for the original 5 documents (kept for backward compatibility). */
    private String loadInlineContent(String id) {
        return switch (id) {
            case "upi-mandate-overview" -> """
                UPI (Unified Payments Interface) mandates allow businesses and consumers to set up
                recurring auto-debit payments on any UPI-linked bank account. Mandates are registered
                via NPCI's mandate management system and require explicit customer consent through
                bank authentication (UPI PIN or biometric). Key mandate attributes:
                - mandateRef: Unique NPCI reference for the mandate
                - remitterAccountNumber: Customer's bank account
                - remitterBank: Bank IFSC/name
                - amount / frequency: Fixed/variable, daily/weekly/monthly/one-time
                - validityPeriod: Start and end dates
                Switching a mandate means transferring the auto-debit authority from one bank account
                to another while maintaining the same mandate terms. Regulatory limit: NACH mandates
                above ₹15,000 require Additional Factor Authentication (AFA) at execution time.
                """;
            case "nach-mandate-switching" -> """
                National Automated Clearing House (NACH) is RBI's centralized platform for bulk and
                recurring debit transactions (EMIs, SIPs, insurance premiums, utility bills).
                Mandate Switch Workflow:
                1. Originator (business) submits a switch request with new remitter bank details.
                2. PaSS validates mandate status — only ACTIVE mandates can be switched.
                3. System checks behavioral similarity score of the requestor against stored profile.
                4. If similarity > AUTO_EXECUTE_THRESHOLD (0.95): mandate switch is executed immediately.
                5. If similarity < threshold: request is escalated to HITL operator for review.
                6. On approval, NPCI API call updates the mandate and returns confirmationRef.
                7. Audit log is written to pass_audit database with XAI reasoning.
                Critical rules:
                - Cannot switch to a bank that is on the RBI restricted list.
                - Mandate switches are irreversible within the same business day.
                - Maximum 3 switches per mandate per year per RBI circular DPSS.CO.PD No.1324.
                """;
            case "fraud-detection-policy" -> """
                The PaSS fraud detection system uses multi-modal behavioral telemetry to compute a
                real-time similarity score between the current user session and their stored behavioral
                fingerprint.
                Behavioral signals captured:
                - Device fingerprint: hardware model, OS version, screen resolution
                - Biometric patterns: keystroke dynamics (dwell time, flight time), touch pressure
                - Network signals: IP geolocation, ISP, VPN detection
                - Session patterns: time-of-day, request frequency, navigation path
                - Transaction patterns: typical transfer amounts, frequency, target banks
                Fraud thresholds:
                - Score ≥ 0.95: Autonomous execution — no human review needed
                - Score 0.80–0.95: Flag for monitoring — execute but alert compliance
                - Score < 0.80: Escalate to HITL — freeze mandate switch
                Automatic block triggers (regardless of score):
                - Velocity check: more than 3 mandate switches in 24 hours
                - Geo-anomaly: request origin > 500 km from usual location
                - New device: first-time device fingerprint with high-value transaction
                """;
            case "hitl-escalation-policy" -> """
                HITL (Human-In-The-Loop) is the override mechanism that freezes AI-autonomous execution
                and routes payment decisions to qualified human operators for review.
                Escalation conditions:
                1. Behavioral similarity score below configured threshold (default 0.95)
                2. Fraud signals detected by the Context Agent
                3. Transaction amount above ₹1,00,000 for first-time mandate holders
                4. User explicitly requests human review
                5. System-detected anomaly in the mandate target bank details
                HITL workflow:
                1. Agent creates an escalation record in pass_hitl.hitl_escalations
                2. Escalation is assigned to an operator via the operator dashboard
                3. Operator has 4-hour SLA to review and decide: APPROVE or REJECT
                4. If no action within SLA, escalation auto-expires and user is notified
                5. Decision is audit-logged with operator ID and reasoning
                Operator appeal: Users can file an appeal via PUT /api/v1/hitl/escalations/{id}/appeal
                with additional documentation. Appeals are handled by senior operators.
                """;
            case "pass-orchestrator-guide" -> """
                The PaSS (Payment and Settlement Switch) Orchestrator is an AI agent that processes
                natural language payment intents and executes them within RBI regulatory constraints.
                Decision flow for each user intent:
                1. Parse intent: identify action (switch/query/cancel), target bank, amount, mandate ID
                2. Validate inputs: check against known banks, verify mandate exists and is ACTIVE
                3. Context evaluation: call fraud detection, check behavioral similarity
                4. Threshold check: compare score to AUTO_EXECUTE_THRESHOLD
                5. Execute or escalate: call LedgerTools.switchMandate() or create HITL record
                6. Return structured response with: decision, reasoning, confirmationRef or escalationId
                Response format guidelines:
                - Always include the decision (EXECUTED/ESCALATED/REJECTED) prominently
                - Provide clear reasoning for the decision
                - For escalations: include the escalationId for user tracking
                - For rejections: explain regulatory reason and suggest corrective action
                - Keep responses concise — under 200 words for routine operations
                Supported bank list (major Indian banks): SBI, HDFC, ICICI, Axis, Kotak, PNB,
                Bank of Baroda, Canara Bank, Union Bank, IndusInd, Yes Bank, IDFC First.
                """;
            default -> null;
        };
    }
}
