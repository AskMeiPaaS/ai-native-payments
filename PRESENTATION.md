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
│  ┌─────────────┐   ┌──────────────┐   ┌─────────────────────────┐  │
│  │  Stage 1     │   │  Stage 2      │   │  Stage 3               │  │
│  │  CLASSIFY    │──▶│  EXECUTE      │──▶│  FORMAT                │  │
│  │  (LLM Call)  │   │  (Java Only)  │   │  (LLM Call or Bypass)  │  │
│  └──────┬───────┘   └──────┬────────┘   └────────────────────────┘  │
│         │                  │                                         │
│  ┌──────▼───────┐   ┌──────▼────────────────────────────────┐       │
│  │ Intent Only: │   │ Tool Execution:                       │       │
│  │ ACTION       │   │ • transferFunds → ACID commit         │       │
│  │ BENEFICIARY  │   │ • receiveFunds  → ACID commit         │       │
│  │ AMOUNT       │   │ • switchMandate → ACID commit         │       │
│  │ CHANNEL      │   │ • checkBalance  → read                │       │
│  └──────────────┘   │ • executeMongoQuery → filtered read   │       │
│                     └───────────────────────────────────────┘       │
└───────┬──────────────────┬──────────────────────┬───────────────────┘
        │                  │                      │
   ┌────▼─────┐    ┌──────▼───────┐    ┌─────────▼──────────┐
   │  Ollama  │    │   MongoDB    │    │    Voyage AI API   │
   │  Local   │    │  Atlas Local │    │      (Cloud)       │
   │  LLM     │    │  4 Databases │    │  Embed + Rerank    │
   └──────────┘    └──────────────┘    └────────────────────┘
```

### Three-Stage Pipeline — LLM Is Not the Bottleneck

| Stage | What Happens | LLM Involved? |
|-------|-------------|----------------|
| **Stage 1: Classify** | LLM reads user intent → outputs 4 fields: ACTION, BENEFICIARY, AMOUNT, CHANNEL | ✅ Single LLM call |
| **Stage 2: Execute** | Deterministic Java tool execution — ACID transactions, MongoDB queries, balance checks | ❌ No LLM |
| **Stage 3: Format** | For general queries: LLM formats response. For tool results: bypassed (direct return) | ✅/❌ Conditional |

> **Key insight:** The classifier never generates code, queries, or tool calls. MongoDB filters are built programmatically from user keywords — making the system reliable regardless of LLM quality.

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
| **DefaultToolExecutor** | Registered at boot for deterministic tool dispatch in the two-fold path |

### Tool Registry

```java
@PostConstruct
public void init() {
    // Register every @Tool method as a callable executor
    for (Method method : ledgerTools.getClass().getDeclaredMethods()) {
        if (method.isAnnotationPresent(Tool.class)) {
            toolExecutors.put(method.getName(),
                new DefaultToolExecutor(ledgerTools, method));
        }
    }
}
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
| Classify user intent (4 fields) | Generate MongoDB queries |
| Select payment channel from RAG context | Execute transactions |
| Format human-readable responses | Access PII directly |
| Tag confidence (HIGH/MEDIUM/LOW) | Override business rules |
| Ask clarification on LOW confidence | Bypass amount/channel validation |

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
| **Query Security** | `executeMongoQuery()` whitelists fields; always injects `userId` — no cross-user data access |
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

1. **AI is the routing engine** — no hard-coded `if/else` for channel selection; the LLM reads RAG-retrieved RBI rules and decides
2. **LLM is NOT the bottleneck** — classifier outputs only 4 fields; all query filtering and tool execution is deterministic Java
3. **MongoDB is the unified brain** — vector search, ACID ledger, encrypted PII, session memory, audit trail — one technology, four isolated databases
4. **Voyage AI provides semantic quality** — high-dimensional embeddings + reranking without competing for local compute
5. **Local LLM = data sovereignty** — no payment data or PII ever leaves the infrastructure
6. **Every decision is auditable** — tool calls, channel choices, escalations, and overrides logged with session correlation
7. **Humans stay in the loop** — any decision can be appealed; operators have full approve/deny/override control
