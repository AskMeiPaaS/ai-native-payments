package com.ayedata.service;

import com.ayedata.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Payment Method Recommendation Service
 *
 * This service only retrieves routing context from RAG.
 * Channel selection is performed by the LLM during orchestration, not by deterministic Java code.
 */
@Service
public class PaymentMethodRecommendationService {
    private static final Logger log = LoggerFactory.getLogger(PaymentMethodRecommendationService.class);

    private final RagService ragService;

    public PaymentMethodRecommendationService(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Retrieve RAG context for payment method/channel selection. LLM must decide the channel.
     *
     * @param amount           Transfer amount in paise (multiply by 100)
     * @param userBehavioralScore Behavioral similarity score (0.0 - 1.0)
     * @param targetBank       Target bank for routing decision
     * @return String with RAG context for LLM
     */
    public String getPaymentChannelRagContext(double amount, double userBehavioralScore, String targetBank) {
        try {
            return ragService.retrieveContext(
                    "payment routing decision matrix amount based channel recommendation risk profile",
                    3
            );
        } catch (Exception e) {
            log.error("Failed to retrieve payment channel RAG context", e);
            return "RAG context unavailable. LLM must use fallback knowledge.";
        }
    }

}
