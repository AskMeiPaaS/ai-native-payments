/**
 * Root package for the AI-Native Payments platform.
 *
 * <p>The codebase is organized by responsibility to keep the platform modular:
 * <ul>
 *   <li>{@code com.ayedata.ai} — agent orchestration, tools, and context enrichment</li>
 *   <li>{@code com.ayedata.audit} — session-aware audit logging, API capture, and compliance hooks</li>
 *   <li>{@code com.ayedata.config} — infrastructure and model/database configuration</li>
 *   <li>{@code com.ayedata.controller} — shared REST and SSE entry points</li>
 *   <li>{@code com.ayedata.domain} — core payment and profile domain models</li>
 *   <li>{@code com.ayedata.hitl} — human review, appeals, operator actions, escalation state</li>
 *   <li>{@code com.ayedata.init} — startup initializers and bootstrap orchestration</li>
 *   <li>{@code com.ayedata.rag} — knowledge ingestion, retrieval, and reranking</li>
 *   <li>{@code com.ayedata.service} — shared business services such as memory and ledger coordination</li>
 * </ul>
 */
package com.ayedata;
