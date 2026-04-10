# AI-Native Payments — PaSS Orchestrator

### An AI-First Financial Orchestration Platform for RBI Payment Switching Service

---

## 🎯 The Problem

India's payment ecosystem is **fragmented across multiple rails** — UPI, UPI Lite, NEFT, RTGS, IMPS, Cheque — each with different limits, settlement speeds, and regulatory constraints.

| Challenge | Impact |
|-----------|--------|
| **Hard-coded routing logic** | Every new RBI rule = code change + release cycle |
| **No contextual awareness** | Same logic for a ₹50 coffee and a ₹5L property settlement |
| **Brittle channel selection** | Nested `if/else` trees that grow with every new payment rail |
| **Siloed fraud detection** | Behavioral signals disconnected from routing decisions |
| **Opaque decisions** | Users and regulators can't see *why* a channel was chosen |

> Traditional payment apps wrap AI around a **fixed rule engine**.
> This platform inverts that — the **AI is the engine**, constrained by deterministic guardrails.

---

## 💡 Why a Payment Switching Service (PaSS)?

The Reserve Bank of India mandates distinct rails for different transaction profiles:

| Payment Rail | Amount Range | Settlement | Use Case |
|-------------|-------------|------------|----------|
| **UPI Lite** | ≤ ₹500 | Instant | Micro-payments, transit, coffee |
| **UPI** | ₹501 – ₹1,00,000 | Instant | P2P, merchant, bills |
| **NEFT** | ₹1,00,001 – ₹1,99,999 | Half-hourly batches | Medium-value transfers |
| **RTGS** | ≥ ₹2,00,000 | Real-time gross | High-value, time-critical |
| **IMPS** | Up to ₹5,00,000 | Instant | 24×7 interbank |
| **Cheque** | Any | T+3 hours (RBI CTS rule) | Legal, government, paper-trail |

### Regulatory Constraints (Indian Payment Law)

| Rule | Detail |
|------|--------|
| Cash ban above ₹2,00,000 | 100% penalty on recipient (Section 269ST) |
| PAN mandatory above ₹50,000 | Cash transactions require documentation |
| AML screening | Mandatory for all transactions above ₹10,00,000 |
| STR filing | Required when suspicious patterns detected |

**A PaSS must route intelligently** — selecting the optimal rail based on amount, urgency, risk, and regulation — not just the user's preference.

---

## 🧬 Why AI-Native Applications?

Most financial apps today are **AI-augmented** — a traditional system with an LLM bolted on as a chatbot, recommendation sidebar, or post-hoc analytics filter. The core decision-making remains hard-coded rules.

An **AI-native** application inverts this: the LLM is the primary decision engine, constrained by deterministic guardrails (ACID transactions, business rules, regulatory limits). The difference is structural, not cosmetic.

| Aspect | AI-Augmented (Traditional) | AI-Native (This Platform) |
|--------|---------------------------|---------------------------|
| Routing logic | Hard-coded `if/else` trees | LLM selects channel from RAG-retrieved RBI rules |
| Intent parsing | Regex / keyword matching | LLM classifies natural language into structured intents |
| User interface | Forms with dropdowns | Conversational — user speaks naturally |
| Memory | None or session cookies | Semantic memory — vector-embedded conversation history |
| Explainability | Log files | Auditable chain: intent → tool call → result → response |
| Adaptation | Code change + release cycle | Update RAG corpus or prompt — no redeployment |

### Pros of AI-Native

| Advantage | Detail |
|-----------|--------|
| **Natural language UX** | Users say "pay Ramesh ₹50K" instead of filling 5 form fields |
| **Adaptive routing** | New RBI rule? Update the RAG document — no code change |
| **Semantic understanding** | "How much do I owe Priya?" triggers a counterparty search, not a keyword match |
| **Multi-turn context** | LLM remembers prior turns via temporal memory — follow-ups work naturally |
| **Explainable decisions** | Every tool call, channel selection, and escalation is logged and auditable |
| **Compound intents** | "Pay Ramesh and show my balance" — LLM can orchestrate multiple tools in one turn |

### Cons of AI-Native

| Challenge | Mitigation in This Platform |
|-----------|-----------------------------|
| **Non-determinism** | Temperature 0.1 (near-deterministic); ACID transactions enforce correctness regardless of LLM output |
| **Latency** | Local LLM avoids network round-trips; token streaming delivers partial responses immediately |
| **Hallucination risk** | LLM cannot bypass `@Tool` method business rules; amount/channel validation is deterministic Java |
| **Observability** | Full token metrics (input/output per stage), audit trail per request, SSE stage events |
| **Cost at scale** | Local Ollama = zero marginal cost; Voyage AI embeddings are the only paid API call |
| **Testability** | Each `@Tool` is a unit-testable Java method; LLM behavior is constrained by system prompt rules |
| **Security** | LLM never sees PII (Queryable Encryption); all data stays on-premise (local inference) |

> **Bottom line:** AI-native is not about replacing rules with randomness — it's about letting the LLM handle the *fuzzy* parts (language understanding, channel selection, response formatting) while deterministic code handles the *critical* parts (money movement, validation, compliance).

---

## 🔍 Fraud Detection & Risk Management

The platform features an advanced, AI-native fraud detection system that goes beyond traditional rule-based approaches:

### RAG-Supplemented Fraud Intelligence

Unlike static fraud rules, this system retrieves contextual knowledge from a comprehensive RAG corpus:

| Traditional Approach | AI-Native Approach |
|---------------------|-------------------|
| Hard-coded thresholds | Dynamic risk scoring with RAG context |
| Rule-based signal detection | LLM-powered pattern analysis |
| Static penalty multipliers | Adaptive risk adjustments based on retrieved knowledge |
| Manual rule updates | Knowledge base updates without code changes |

### Fraud Analysis Pipeline

```
User Intent → RAG Retrieval → Signal Detection → Composite Scoring → Action Determination
```

#### 1. Contextual Knowledge Retrieval
- Retrieves fraud patterns, regulatory thresholds, and mitigation strategies from embedded documents
- Uses Voyage AI reranking for relevance scoring
- Provides domain-specific context for risk assessment

#### 2. Multi-Signal Analysis
| Signal Type | Detection Logic | Risk Impact |
|-------------|----------------|-------------|
| **High-Value Transactions** | Amount > ₹1,00,000 | High risk multiplier (0.8x) |
| **Geographic Anomalies** | Location mismatch with user profile | Critical risk (0.7x) |
| **Device Patterns** | New/unrecognized device fingerprints | Medium risk (0.9x) |
| **Timing Anomalies** | Transactions outside normal hours | Moderate risk (0.85x) |

#### 3. Composite Risk Scoring
```
Final Risk Score = Behavioral Similarity × Signal Penalties × RAG Context Weight
```

#### 4. Intelligent Action Thresholds
| Risk Score | Action | Outcome |
|------------|--------|---------|
| ≥ 0.95 | **APPROVE** | Automatic processing with audit logging |
| 0.80 - 0.95 | **MONITOR** | Proceed with enhanced monitoring |
| < 0.80 | **ESCALATE** | Route to Human-in-the-Loop (HITL) for review |
| Critical signals | **BLOCK** | Immediate rejection with fraud alert |

### Integration with Transaction Flow

Fraud analysis is seamlessly integrated into the payment orchestration:

1. **Pre-Transaction Assessment**: Fraud context analyzed before any balance changes
2. **Real-time Decision Making**: Actions enforced at tool execution time
3. **Full Audit Trail**: All signals, scores, and RAG context stored in transaction records
4. **Explainable Outcomes**: Regulators and users can see *why* decisions were made

### Regulatory Compliance

The system ensures compliance with Indian financial regulations:
- **AML Screening**: Automatic triggers for transactions above ₹10,00,000
- **STR Filing**: Suspicious pattern detection with escalation protocols
- **PAN Requirements**: Validation for transactions above ₹50,000
- **Cash Ban Enforcement**: Blocks for amounts above ₹2,00,000

This AI-native fraud detection provides intelligent, adaptive protection while maintaining full transparency and regulatory compliance.

---

## 🧰 Technology Introductions

Before diving into how each technology is used, here is what they are and why they were chosen.

### Voyage AI — Semantic Intelligence as a Service

[Voyage AI](https://www.voyageai.com/) is an embedding and reranking model provider founded by researchers from Stanford and MIT. Unlike general-purpose LLMs, Voyage AI specialises in **representation learning** — converting text into dense numerical vectors that capture meaning, not just keywords.

| Model | Purpose | Why It Matters |
|-------|---------|----------------|
| `voyage-4` | Dense embedding (1024 dimensions) | State-of-the-art retrieval quality; outperforms OpenAI `text-embedding-3-large` on most benchmarks |
| `rerank-lite-1` | Cross-encoder reranking | Scores query–document relevance beyond cosine similarity; lightweight enough for real-time use |

**Why Voyage AI over local embeddings?** Small local models (384-dim) lose semantic nuance — "pay ₹50K via NEFT" and "transfer funds through national electronic transfer" may not match. Voyage-4's 1024-dim vectors capture these synonyms. Offloading embeddings to an API also frees local compute for LLM inference.

### LangChain4j — LLM Application Framework for Java

[LangChain4j](https://docs.langchain4j.dev/) is the Java-native port of the LangChain ecosystem. It provides the plumbing to connect LLMs, tools, memory stores, and retrieval pipelines into a cohesive agent — without writing raw HTTP calls or managing prompt serialisation manually.

Key abstractions used in this platform:

| Abstraction | What It Does |
|-------------|-------------|
| `AiServices` | Creates a proxy from a Java interface — method calls become LLM interactions with automatic tool dispatch |
| `@Tool` | Annotates a Java method as callable by the LLM; LangChain4j handles argument marshalling and result injection |
| `@ToolMemoryId` | Injects session context (e.g., `userId`) into tool calls without exposing it in the prompt |
| `MessageWindowChatMemory` | Sliding-window memory (N messages) persisted to any store (here: MongoDB) |
| `StreamingChatModel` | Token-by-token response streaming for real-time SSE delivery |
| `DefaultToolExecutor` | Executes `@Tool` methods programmatically outside the LLM loop (used for health checks) |

**Why LangChain4j over raw Ollama HTTP calls?** Without LangChain4j, you would manually parse tool-call JSON from LLM output, dispatch to the right method, re-inject the result, and manage conversation memory — hundreds of lines of brittle plumbing that LangChain4j handles declaratively.

### Ollama — Run Any Open LLM Locally

[Ollama](https://ollama.com/) is an open-source runtime for running large language models on consumer and server hardware. It wraps model quantisation, memory management, and inference behind a simple REST API.

| Feature | Detail |
|---------|--------|
| **Model library** | 100+ models: Qwen, LLaMA, Mistral, DeepSeek, Gemma, Phi — pull like Docker images |
| **Quantisation** | Automatic GGUF quantisation (Q4_K_M, Q5_K_M) for CPU-friendly inference |
| **REST API** | `/api/chat`, `/api/generate`, `/api/embeddings` — standard HTTP, no SDK lock-in |
| **Docker-native** | Official `ollama/ollama` image; runs as a sidecar service in `docker-compose.yaml` |
| **Model management** | `ollama pull`, `ollama list`, `ollama rm` — declarative model lifecycle |

**This platform uses Qwen 2.5** (`qwen2.5:latest`) — a multilingual, tool-calling-capable model from Alibaba Cloud. It supports function calling natively, making it compatible with LangChain4j's `@Tool` dispatch without prompt hacking.

**Why Ollama over cloud LLMs?** For a financial platform handling PII and payment data, sending prompts to third-party servers (OpenAI, Anthropic) creates compliance risk. Ollama keeps all inference on-premise with zero marginal cost per token.

---

## 🎭 Different Roles of the LLM

The LLM in this platform is not a single monolithic chatbot. It plays **four distinct roles**, each with a different system prompt, memory scope, and trust boundary:

### Role 1: Classifier (Stage 1)

| Property | Value |
|----------|-------|
| **Purpose** | Extract structured intent from natural language |
| **Input** | User message + compact context (balance, recent txns) |
| **Output** | 4 fields: `ACTION`, `BENEFICIARY`, `AMOUNT`, `CHANNEL` |
| **Memory** | None — stateless, single-shot |
| **Trust level** | Low — output is parsed and validated; invalid output triggers retry |

```
User: "pay ₹5000 to Ramesh via UPI"
  → ACTION: TRANSFER
  → BENEFICIARY: Ramesh
  → AMOUNT: 5000
  → CHANNEL: UPI
```

The classifier is a **direct `ChatRequest`** (not an AiServices proxy) so we can capture exact input/output token counts for observability.

### Role 2: Tool Orchestrator (Stage 2)

| Property | Value |
|----------|-------|
| **Purpose** | Decide which `@Tool` methods to call and with what arguments |
| **Input** | RAG-enriched intent (balance, knowledge chunks, conversation history) |
| **Output** | Tool calls dispatched via LangChain4j, then a formatted response |
| **Memory** | 6-message sliding window (persisted to MongoDB) |
| **Trust level** | Medium — LLM decides tool calls, but `@Tool` methods enforce business rules |

The `Supervisor` and `StreamingSupervisor` AiServices proxies have full `@Tool` access. LangChain4j intercepts the LLM's tool-call requests, executes the corresponding Java method, injects the result back into the conversation, and lets the LLM format the final response.

```
LLM thinks: "User wants to transfer ₹5000 to Ramesh. I should call transferFunds."
  → LangChain4j intercepts tool call
  → Executes transferFunds("Ramesh", 5000) — ACID commit
  → Result injected: "SUCCESS: ₹5,000.00 transferred to Ramesh via UPI"
  → LLM formats: "Done! ₹5,000 has been transferred to Ramesh via UPI."
```

### Role 3: Response Formatter

| Property | Value |
|----------|-------|
| **Purpose** | Convert raw tool results and data into human-readable, contextual responses |
| **Input** | Tool execution results + conversation context |
| **Output** | Natural language response with relevant details |
| **Memory** | Shared with Tool Orchestrator (same AiServices proxy) |
| **Trust level** | Low — formatting only; cannot alter committed transactions |

The formatter role is **merged with the Tool Orchestrator** in a single LLM interaction. After the tool returns its result, the LLM uses the same turn to compose the response — no separate call needed.

### Role 4: Conversational Agent (General Queries)

| Property | Value |
|----------|-------|
| **Purpose** | Answer questions about payments, channels, regulations, and account activity |
| **Input** | RAG-enriched context with payment channel docs, regulatory frameworks |
| **Output** | Informative response grounded in retrieved knowledge |
| **Memory** | 6-message sliding window |
| **Trust level** | Medium — grounded in RAG; no tool execution for informational queries |

```
User: "What are the NEFT settlement timings?"
  → RAG retrieves neft-payment-channel.txt
  → LLM answers from retrieved knowledge (no tool call)
```

### Role Boundaries

```
┌─────────────────────────────────────────────────────────────┐
│                    LLM Trust Boundaries                     │
│                                                             │
│  ┌──────────┐  ┌──────────────┐  ┌──────────┐  ┌────────┐  │
│  │Classifier│  │Tool          │  │Response  │  │Conver- │  │
│  │          │  │Orchestrator  │  │Formatter │  │sational│  │
│  │ Stateless│  │ +Tools       │  │ (merged) │  │ +RAG   │  │
│  │ Low trust│  │ Med trust    │  │ Low trust│  │Med trust│  │
│  └──────────┘  └──────────────┘  └──────────┘  └────────┘  │
│       │               │                            │        │
│       ▼               ▼                            ▼        │
│  Parse & validate  @Tool methods             RAG grounding  │
│  (retry on fail)   enforce rules             (no halluc.)   │
└─────────────────────────────────────────────────────────────┘
```

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        BROWSER (Next.js 14)                         │
│   Chat UI · Balance Dashboard · HITL Operator Panel · Token Metrics │
└─────────────────────────┬───────────────────────────────────────────┘
                          │ SSE / REST
┌─────────────────────────▼───────────────────────────────────────────┐
│                   API GATEWAY (Java 21 + Spring Boot)               │
│                                                                     │
│  ┌─────────────┐   ┌──────────────────────────────────────────┐     │
│  │  Stage 1     │   │  Stage 2 — LLM-Led Tool Orchestration    │     │
│  │  CLASSIFY    │──▶│  LLM decides tools → LangChain4j execs   │     │
│  │  (LLM Call)  │   │  → LLM formats response                 │     │
│  └──────┬───────┘   └──────┬───────────────────────────────────┘     │
│         │                  │                                         │
│  ┌──────▼───────┐   ┌──────▼────────────────────────────────┐       │
│  │ Intent Only: │   │ @Tool Methods (via AiServices proxy): │       │
│  │ ACTION       │   │ • transferFunds → ACID commit         │       │
│  │ BENEFICIARY  │   │ • receiveFunds  → ACID commit         │       │
│  │ AMOUNT       │   │ • switchMandate → ACID commit         │       │
│  │ CHANNEL      │   │ • checkBalance  → read                │       │
│  └──────────────┘   │ • recentTransactions → filtered read  │       │
│                     │ • searchTransactions → search read    │       │
│                     └───────────────────────────────────────┘       │
└───────┬──────────────────┬──────────────────────┬───────────────────┘
        │                  │                      │
   ┌────▼─────┐    ┌──────▼───────┐    ┌─────────▼──────────┐
   │  Ollama  │    │   MongoDB    │    │    Voyage AI API   │
   │  Local   │    │  Atlas Local │    │      (Cloud)       │
   │  LLM     │    │  4 Databases │    │  Embed + Rerank    │
   └──────────┘    └──────────────┘    └────────────────────┘
```

### Two-Stage Pipeline — LLM-Led Tool Orchestration

| Stage | What Happens | LLM Involved? |
|-------|-------------|----------------|
| **Stage 1: Classify** | LLM reads user intent → outputs 4 fields: ACTION, BENEFICIARY, AMOUNT, CHANNEL | ✅ Single LLM call |
| **Stage 2: Orchestrate** | LLM decides which `@Tool` to call → LangChain4j executes → LLM formats the response | ✅ LLM + Tool execution |

> **Key insight:** The LLM has full tool access via LangChain4j AiServices, but `@Tool` methods enforce all business rules — amount validation, channel selection, ACID commits. The LLM orchestrates; Java enforces.

---

## 🗄️ MongoDB — The Semantic Brain

MongoDB serves as the **unified data and memory layer** across four isolated databases:

### Four-Database Isolation

| Database | Purpose | Key Collections |
|----------|---------|-----------------|
| `pass_main` | Core ledger, user profiles, RAG corpus | `user_profiles`, `transactions`, `rag_knowledge` |
| `pass_audit` | Immutable compliance trail | `system_audit_logs` |
| `pass_hitl` | Human escalation state | `hitl_escalations` |
| `pass_memory` | Session chat memory, temporal recall | `agent_chat_memory`, `memory_timeline`, `session_registry` |

### Why MongoDB?

#### 1. Vector Search — Semantic Memory

MongoDB Atlas Vector Search powers two critical capabilities:

```
User prompt → Voyage AI embedding (1024-dim) → $vectorSearch → Rerank → Context
```

- **RAG Knowledge Retrieval:** Payment channel docs, regulatory frameworks, routing matrices are embedded and retrieved semantically — the LLM only sees relevant knowledge
- **Temporal Recall:** Prior conversation turns are vector-embedded and recalled per session, giving the agent memory across multi-turn interactions

#### 2. ACID Transactions — Ledger Safety

Every payment tool (`transferFunds`, `receiveFunds`, `switchMandate`) commits through `MongoLedgerService` with multi-document ACID transactions:

```java
@Transactional
public void commitSwitchAtomic(String userId, String beneficiary,
                                String targetBank, double amount, ...) {
    // Debit sender + Credit receiver + Write transaction record
    // All-or-nothing — no partial state
}
```

#### 3. Queryable Encryption — PII Protection

Sensitive fields (`email`, `phone`, `bank_account`) are encrypted at the driver level using MongoDB Queryable Encryption:

- **Dashboard:** Shows `ar•••@domain.com`, `+91••••••3210`
- **Decrypt:** Only on explicit `GET /reveal-pii` with `[PII-REVEAL]` audit entry
- **LLM never sees PII** — sanitized before reaching any prompt

#### 4. TTL & Lifecycle Management

| Collection | TTL | Purpose |
|-----------|-----|---------|
| `agent_chat_memory` | 7 days | Session chat context auto-expires |
| `memory_timeline` | 7 days | Temporal recall vectors auto-expire |
| `session_registry` | 1 hour | Active session mapping auto-expires |

#### 5. Compound Indexes for Query Performance

```
transactions: (userId ASC, createdAt DESC)
transactions: (userId ASC, instructionType ASC, createdAt DESC)
```

These support the programmatic filter builder — filtered queries on transaction type, payment method, and time range execute efficiently without collection scans.

---

## 🧭 Voyage AI — Embeddings & Reranking

Voyage AI provides the **semantic understanding layer** — converting text into meaning and ranking results by relevance.

### Embedding: `voyage-4` (1024 dimensions)

| Capability | How It's Used |
|-----------|---------------|
| **RAG Knowledge** | 7 RBI payment channel documents embedded into `rag_knowledge` collection |
| **Temporal Memory** | Each conversation turn embedded for later semantic recall |
| **Behavioral Telemetry** | Device/biometric signals vectorized for fraud baseline matching |

```
"pay ₹50,000 to Ramesh via NEFT"
        │
        ▼ Voyage AI embed
   [0.102, -0.441, 0.882, ...]  (1024 floats)
        │
        ▼ MongoDB $vectorSearch
   Top-K matching knowledge chunks
        │
        ▼ Voyage AI rerank
   Most relevant 2 chunks returned
```

### Reranking: `rerank-lite-1`

After vector search retrieves candidate chunks, the reranker scores them by actual relevance to the query — not just vector proximity:

| Without Reranking | With Reranking |
|-------------------|----------------|
| Top result might be cosmetically similar but wrong context | Top result is the most relevant payment channel doc |
| "UPI" query might return "UPI Lite" doc first | Correct UPI doc ranked first, UPI Lite second |

**Configuration:**
```
EMBEDDING_MODEL_NAME=voyage-4       # 1024-dimensional dense embeddings
RERANKER_MODEL_NAME=rerank-lite-1   # Lightweight relevance scoring
RERANKER_TOP_K=2                    # Return top 2 reranked chunks
```

### Why Voyage AI (Not Local Embeddings)?

| Factor | Local Embedding | Voyage AI API |
|--------|----------------|---------------|
| Quality | Small models (384-dim) | State-of-the-art (1024-dim) |
| Latency | ~50ms but CPU-bound | ~100ms via API |
| Memory | Competes with LLM for RAM | Zero local footprint |
| Maintenance | Model updates = redeploy | Always latest |

> The LLM runs locally (CPU-intensive). Offloading embeddings to Voyage AI keeps the local machine focused on inference.

---

## 🔗 LangChain4j — The Orchestration Bus

LangChain4j (Java) is the **nervous system** connecting the LLM, tools, memory, and RAG into a cohesive agent.

### What LangChain4j Provides

| Capability | Implementation |
|-----------|----------------|
| **@Tool Binding** | Java methods annotated with `@Tool` are exposed to the LLM as callable functions |
| **@ToolMemoryId** | Session ID injected into tool calls for per-user scoping — LLM never sees `userId` |
| **AiServices Proxy** | `Supervisor` and `StreamingSupervisor` interfaces backed by LLM with automatic tool dispatch |
| **Chat Memory** | `MessageWindowChatMemory` (6-message window) persisted to MongoDB via `MongoChatMemoryStore` |
| **Token Streaming** | `StreamingChatModel` → `TokenStream` → SSE events → browser renders word-by-word |

### LLM-Led Tool Dispatch

The LLM decides which tools to call. LangChain4j intercepts the tool-call request, executes the corresponding `@Tool` Java method, injects the result back into the conversation, and the LLM composes the final response — all in a single interaction:

```
User: "pay ₹5000 to Ramesh"
  → LLM receives RAG-enriched prompt
  → LLM emits tool call: transferFunds("Ramesh", 5000)
  → LangChain4j executes @Tool method (ACID commit)
  → Result: "SUCCESS: ₹5,000.00 transferred via UPI"
  → LLM formats: "Done! ₹5,000 transferred to Ramesh via UPI."
```

### Registered Tools

| Tool | Purpose | Trigger |
|------|---------|---------|
| `transferFunds` | ACID debit + route via PaymentSwitchRouter | "pay ₹5000 to Ramesh" |
| `receiveFunds` | ACID credit into user account | "credit ₹1000 to my account" |
| `switchMandate` | Register bank mandate without moving money | "switch my BESCOM bill to ICICI" |
| `checkBalance` | Read current account balance | "what is my balance?" |
| `recentTransactions` | Fetch recent transactions with optional type filter | "show my recent transactions" |
| `searchTransactions` | Search by counterparty name with optional type filter | "show payments to Priya" |

### RAG Pipeline (Context Enrichment)

```
User: "pay ₹50,000 to Ramesh"
         │
         ▼
ContextEnricher.buildEnrichedIntent()
  ├─ Voyage AI embed query → $vectorSearch(rag_knowledge)
  ├─ Voyage AI rerank → top 2 chunks
  ├─ Extract [APPROVED CHANNELS] from chunks
  ├─ TemporalMemoryService → recall 3 prior turns
  ├─ AccountBalanceService → current balance + recent txn summary
  └─ Assemble enriched prompt:
       [YOUR ACCOUNT]    Balance: ₹15,370, Last 5 txns, velocity
       [RELEVANT KNOWLEDGE] NEFT rules, RTGS rules
       [APPROVED CHANNELS]  NEFT, RTGS
       [CONVERSATION HISTORY] prior 3 turns
       [CURRENT REQUEST]  "pay ₹50,000 to Ramesh"
```

---

## 🖥️ Locally Hosted LLM — Ollama + Qwen 2.5

### Why Local LLM?

| Factor | Cloud LLM (GPT-4, Claude) | Local LLM (Ollama) |
|--------|---------------------------|---------------------|
| **Data sovereignty** | Prompts sent to third-party servers | All data stays on-premise |
| **Latency** | Network round-trip + queue wait | Direct inference, no network |
| **Cost** | Per-token billing | Zero marginal cost after setup |
| **Compliance** | PII transit risk | No PII leaves the machine |
| **Availability** | Depends on provider uptime | Runs offline |

> For a **financial platform handling PII and payment data**, local LLM inference is not optional — it's a compliance requirement.

### Model: Qwen 2.5 (Latest)

| Parameter | Value |
|-----------|-------|
| Model | `qwen2.5:latest` |
| Context Window | 8,192 tokens |
| Temperature | 0.1 (near-deterministic) |
| Max Predict | 1,024 tokens |
| Inference Timeout | 900 seconds (CPU-safe) |
| Host | `http://ollama:11434` (Docker internal) |

### What the LLM Does (and Doesn't Do)

| LLM Does | LLM Does NOT |
|----------|-------------|
| Classify user intent (4 fields) | Execute raw MongoDB queries |
| Decide which `@Tool` to call and with what arguments | Override `@Tool` business rules |
| Select payment channel from RAG context | Access PII directly |
| Format human-readable responses from tool results | Bypass amount/channel validation |
| Handle compound intents (multiple tools per turn) | Commit transactions — `@Tool` methods do |
| Answer questions from RAG-retrieved knowledge | Invent facts not in the RAG corpus |

### Streaming Architecture

```
Ollama (Qwen 2.5)
  │ Token-by-token generation
  ▼
LangChain4j StreamingChatModel
  │ onPartialResponse(token)
  ▼
PaSSController (SseEmitter)
  │ SSE event: chunk
  ▼
Next.js Runtime Proxy (zero-buffering)
  │ text/event-stream
  ▼
Browser UI (word-by-word render)
```

**SSE Contract:**
- `start` — connection accepted
- `chunk` — incremental text delta
- `complete` — metadata: `inputTokens`, `outputTokens`, `totalTokens`, `elapsedMs`
- `error` — graceful failure signal

### Token Observability

The UI displays per-request token metrics in the sidebar:

| Metric | Source |
|--------|--------|
| Step 1 Input/Output Tokens | Direct `ChatRequest` to Ollama (classifier) |
| Step 3 Input/Output Tokens | Supervisor/Streaming response (formatter) |
| Total Tokens | Sum of both steps |
| Tokens/second | `outputTokens / elapsedMs × 1000` |

---

## 🛡️ Security & Responsible AI

### Defense Layers

| Layer | Mechanism |
|-------|-----------|
| **PII Protection** | MongoDB Queryable Encryption on `email`, `phone`, `bank_account` |
| **Data Sovereignty** | Local LLM — no prompts leave the infrastructure |
| **Tool Safety** | `@Tool` methods enforce business rules; LLM cannot bypass validation |
| **Query Security** | `@Tool` methods always scope queries to the authenticated `userId` — no cross-user data access |
| **Fraud Detection** | Behavioral vector similarity scoring via `FraudContextService` |
| **HITL Escalation** | Any decision can be appealed; operators approve/deny/override |
| **Audit Trail** | Every tool call, channel decision, and escalation logged in `pass_audit` |
| **Input Validation** | `userId`, `sessionId`, `amount`, `beneficiary` validated at system boundaries |

### HITL Flow

```
User clicks "Appeal"
  → HitlEscalationService.freezeStateAndEscalate()
  → Creates PENDING_HUMAN_REVIEW record in pass_hitl
  → Operator reviews via dashboard
  → Approve / Deny / Override / Cancel
  → Immutable audit record in pass_audit
```

---

## 🧪 Sample Test Queries

### 1. Aggregate debits and credits

```bash
curl -s -X POST http://localhost:8080/api/v1/agent/orchestrate \
  -H 'Content-Type: application/json' \
  -d '{
    "sessionId": "test-pf1",
    "userId": "user001",
    "userIntent": "tell me total value of debits and credits"
  }'
```

**Expected:** Classifier → `QUERY_TRANSACTIONS`, filter `{}`, all transactions fetched with debit/credit/net summary.

### 2. Filtered query — UPI debits

```bash
curl -s -X POST http://localhost:8080/api/v1/agent/orchestrate \
  -H 'Content-Type: application/json' \
  -d '{
    "sessionId": "test-pf2",
    "userId": "user001",
    "userIntent": "show my UPI debits"
  }'
```

**Expected:** Classifier → `QUERY_TRANSACTIONS`, programmatic filter `{"instructionType":"PASS_MONEY_TRANSFER","paymentMethod":"UPI"}`.

### 3. Counterparty search

```bash
curl -s -X POST http://localhost:8080/api/v1/agent/orchestrate \
  -H 'Content-Type: application/json' \
  -d '{
    "sessionId": "test-pf3",
    "userId": "user001",
    "userIntent": "how much I owe to Priya"
  }'
```

**Expected:** Classifier → `QUERY_SEARCH`, `BENEFICIARY: Priya Sharma`, client-side counterparty filtering.

---

## 📦 Tech Stack Summary

| Layer | Technology | Why |
|-------|-----------|-----|
| **Frontend** | Next.js 14 + React + TypeScript | Streaming SSE chat, dashboard, HITL panel |
| **Backend** | Java 21 + Spring Boot 4.1 | Virtual threads, ACID transactions |
| **Orchestration** | LangChain4j 1.12.2 | @Tool binding, AiServices, streaming, memory |
| **Database** | MongoDB Atlas Local | Vector search, ACID, Queryable Encryption, TTL |
| **Embeddings** | Voyage AI `voyage-4` | 1024-dim dense embeddings via API |
| **Reranking** | Voyage AI `rerank-lite-1` | Relevance scoring on retrieved chunks |
| **LLM** | Ollama + Qwen 2.5 (local) | Data sovereignty, zero-cost inference, offline-capable |
| **Containers** | Docker Compose | 4-service stack, secrets via `.env` |

---

## 🚀 Quick Start

```bash
# 1. Configure
cp .env.example .env    # Set VOYAGE_API_KEY

# 2. Launch
docker compose up -d    # First run pulls Qwen 2.5 (~2 GB)

# 3. Verify
curl http://localhost:8080/api/v1/agent/health

# 4. Use
open http://localhost:3000   # Chat UI
```

---

## 🎯 Key Takeaways

1. **AI-native, not AI-augmented** — the LLM is the decision engine, not a bolt-on chatbot; deterministic Java enforces safety
2. **LLM-led tool orchestration** — the LLM decides which `@Tool` to call; LangChain4j executes; `@Tool` methods enforce business rules
3. **Four LLM roles** — Classifier (intent), Orchestrator (tool dispatch), Formatter (response), Conversational Agent (RAG Q&A)
4. **MongoDB is the unified brain** — vector search, ACID ledger, encrypted PII, session memory, audit trail — one technology, four isolated databases
5. **Voyage AI provides semantic quality** — 1024-dim embeddings + reranking without competing for local compute
6. **Ollama keeps data sovereign** — no payment data or PII ever leaves the infrastructure; zero marginal cost per token
7. **LangChain4j is the glue** — AiServices proxy, `@Tool` binding, chat memory, streaming — declarative orchestration in Java
8. **Every decision is auditable** — tool calls, channel choices, escalations, and overrides logged with session correlation
9. **Humans stay in the loop** — any decision can be appealed; operators have full approve/deny/override control
