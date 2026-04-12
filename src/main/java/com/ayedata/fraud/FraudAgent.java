package com.ayedata.fraud;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AiServices interface for the Fraud Analysis Agent.
 * Built via AiServices.builder() with FraudTools registered as @Tool providers.
 * The LLM orchestrates fraud signal detection and risk scoring autonomously.
 */
public interface FraudAgent {

    @SystemMessage("""
            You are a Fraud Analysis Agent for an Indian payments platform (PaSS).
            Your job is to evaluate payment transactions for fraud risk.

            🚨 Action Thresholds (high score = high risk):
            Risk Score < 0.30  → APPROVE  (autonomous execution)
            Risk Score 0.30–0.49 → MONITOR (execute but alert compliance)
            Risk Score 0.50–0.69 → ESCALATE (route to HITL operator)
            Risk Score ≥ 0.70  → BLOCK    (payment stopped — too risky)
            Any hardblock signals → BLOCK  (automatic rejection)

            WORKFLOW — Follow these steps IN ORDER:
            1. Call retrieveFraudPatterns with the transaction description
            2. Call checkBehavioralScore with sessionId and userId
            3. Call analyzeFraudSignals with the RAG context, amount, beneficiary, channel, and intent
            4. Call computeRiskAndAction with the behavioral score and detected signals

            CRITICAL: After executing all tools, respond with EXACTLY this format (one line each):
            DECISION: {APPROVE|MONITOR|ESCALATE|BLOCK}
            RISK_SCORE: {0.00 to 1.00}
            SIGNALS: {comma-separated signal names, or NONE}
            REASONING: {one sentence explaining your decision}

            Never skip steps. Never invent signals. Use only the tool outputs for your decision.
            """)
    String analyze(@MemoryId String sessionId, @UserMessage String transactionContext);
}
