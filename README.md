# AI-Native PaSS Orchestrator

**AI-Native Payments** is an agentic financial orchestration engine built for the **RBI Payments Switching Service (PaSS)**. It demonstrates how a production-grade multi-agent system can reason, route, and execute banking operations autonomously — with zero hardcoded business logic.

> **License:** MIT — see [LICENSE](LICENSE)

---

## ⚠️ Security Disclaimer

> **Reference implementation only. Not for production use with real funds.**
>
> Missing before any production deployment:
> - Authentication / authorization (Spring Security + JWT)
> - Rate limiting and API key management
> - HTTPS / TLS on all communication surfaces
> - Real payment processor integration (RBI-authorized)
> - Security audit, penetration testing, PCI-DSS compliance

---

## What Makes This AI-Native

Traditional payment apps wrap AI around a fixed rule engine. Here the inverse is true:

| Concern | How It Works |
|---------|-------------|
| **Payment routing** | LLM reads RAG-retrieved RBI channel rules and decides. No Java `if/else`. |
| **Channel selection** | Only channels found in the retrieved knowledge chunks are offered to the LLM (`[APPROVED CHANNELS]`). |
| **Channel validation** | `LedgerTools.validateChannelForAmount()` cross-checks the LLM's chosen channel against RBI amount rules at tool-call time. On mismatch it returns a `CHANNEL_MISMATCH:` sentinel with the correct channel so the LLM automatically re-invokes with the corrected value. |
| **Fraud context** | `FraudContextService` integrates RAG-retrieved fraud patterns with behavioral telemetry scoring; composite risk assessment determines APPROVE/MONITOR/ESCALATE/BLOCK actions, with full audit trail in transaction records. |
| **Ledger execution** | `@Tool`-annotated Java methods expose ACID-safe operations the LLM can call by name. All three tools (`transferFunds`, `receiveFunds`, `switchMandate`) commit to MongoDB via `MongoLedgerService`. |
| **Memory** | Temporal turns are vector-embedded and recalled per session. RAG knowledge is reranked per query. |
| **Human oversight** | Any decision can be appealed; operators approve / deny / override via the HITL dashboard. |
| **Token performance** | `OllamaMetricsScheduler` passively records real `TokenUsage` from every completed request. The SSE `complete` event carries `inputTokens`, `outputTokens`, `totalTokens`, and `elapsedMs`; the `Sidebar` component renders a live token/s panel. |
| **Confidence assessment** | LLM responses include `[Confidence: HIGH/MEDIUM/LOW]` tags; LOW confidence triggers clarification questions instead of tool calls. |
| **Error handling** | Contextual error messages (e.g., "AI took too long" instead of stack traces) for LLM failures; friendly SSE error events prevent verbose exceptions from reaching users. |

---

## 🔍 Enhanced Fraud Detection with RAG Supplementation

The platform features an advanced fraud detection system that combines behavioral telemetry with contextual intelligence from RAG-retrieved knowledge:

### Fraud Analysis Components

| Component | Implementation | Purpose |
|-----------|----------------|---------|
| **FraudContextService** | RAG-integrated analysis engine | Retrieves fraud patterns from knowledge base, analyzes behavioral signals, computes composite risk scores |
| **Risk Scoring** | Composite algorithm | Behavioral similarity penalized by fraud signals (HIGH_VALUE_TRANSACTION: *0.8, GEO_ANOMALY: *0.7, etc.) |
| **Action Thresholds** | Dynamic decision logic | APPROVE ≥0.95, MONITOR 0.80-0.95, ESCALATE <0.80, BLOCK for critical signals |
| **Audit Integration** | MongoLedgerService | Fraud analysis results stored in transaction records with AgentReasoning snapshots |
| **Signal Detection** | Pattern matching | HIGH_VALUE_TRANSACTION, GEO_ANOMALY_DETECTED, NEW_DEVICE_PATTERN, UNUSUAL_TIMING |

### Fraud Knowledge Base

The RAG corpus includes comprehensive fraud detection policies covering:
- Behavioral anomaly patterns
- Regulatory compliance thresholds
- Automatic block triggers for suspicious activities
- Risk mitigation strategies

### Transaction Flow with Fraud Checks

1. User intent parsed by Supervisor Agent
2. FraudContextService analyzes context before balance debits
3. Risk score determines action: APPROVE (proceed), MONITOR (log), ESCALATE (HITL), BLOCK (reject)
4. All decisions logged in audit trail with RAG context and fraud signals

This ensures intelligent, context-aware fraud prevention while maintaining full explainability and compliance.

---

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Frontend | Next.js 14 + React + TypeScript | Streaming chat UI, balance dashboard, HITL operator panel |
| Backend | Java 21 + Spring Boot + LangChain4j | Multi-agent orchestration; virtual threads |
| Memory store | MongoDB Local Atlas | Four isolated databases (main, audit, hitl, memory) with vector search |
| Embeddings | Voyage AI `voyage-4` (API) | 1024-dim dense embeddings for RAG and temporal recall |
| Reranking | Voyage AI `rerank-lite-1` (API) | Relevance scoring on retrieved knowledge chunks |
| LLM | Ollama + Qwen 2.5 3B (local) | Real-time token streaming; configurable via `LLM_MODEL_NAME`; context window `LLM_NUM_CTX=8192` |
| Containers | Docker Compose | Four-service stack; all secrets injected via `.env` |

---

## Project Structure

```
ai-native-payments/
│
├── src/main/java/com/ayedata/
│   ├── AiNativePaymentsApplication.java
│   │
│   ├── ai/                                   # Agent core
│   │   ├── agent/
│   │   │   ├── ContextEnricher.java          # RAG + temporal recall → enriched prompt
│   │   │   └── PaSSOrchestratorAgent.java    # Supervisor + streaming supervisor
│   │   └── tools/
│   │       └── LedgerTools.java              # @Tool methods: transferFunds, receiveFunds, switchMandate
│   │
│   ├── audit/                                # Compliance & observability
│   │   ├── config/ApiAuditLoggingFilter.java # HTTP request/response capture
│   │   ├── domain/AuditRecord.java
│   │   ├── exception/GlobalAuditExceptionHandler.java
│   │   ├── init/AuditIndexInitializer.java
│   │   └── service/AuditLoggingService.java
│   │
│   ├── config/                               # Spring / infrastructure wiring
│   │   ├── AiConfig.java
│   │   ├── EmbeddingModelConfig.java
│   │   ├── EncryptionConfig.java             # MongoDB Queryable Encryption
│   │   ├── LlmModelConfig.java
│   │   ├── MongoChatMemoryStore.java         # LangChain4j chat memory → MongoDB
│   │   ├── VoyageAiEmbeddingModelImpl.java
│   │   ├── VoyageAiScoringModel.java
│   │   └── *DatabaseConfig.java              # Four isolated MongoClient beans
│   │
│   ├── controller/                           # REST + SSE entry points
│   │   ├── PaSSController.java               # /api/v1/agent/orchestrate[-stream]
│   │   ├── AccountController.java            # /api/v1/account/dashboard, /topup, /reveal-pii
│   │   ├── HealthCheckController.java
│   │   └── SseEmitterHelper.java             # Safe send / complete helpers
│   │
│   ├── domain/                               # Core entities
│   │   ├── FinancialData.java                # Transaction / ledger record
│   │   ├── UserProfile.java
│   │   ├── TransactionRecord.java
│   │   ├── AgentReasoning.java
│   │   └── EncryptionMetadata.java
│   │
│   ├── hitl/                                 # Human-in-the-Loop subsystem
│   │   ├── controller/
│   │   │   ├── AppealController.java         # POST /api/v1/agent/appeal
│   │   │   └── HitlOperatorController.java   # /api/v1/operator/escalations/**
│   │   ├── domain/HitlEscalationRecord.java
│   │   ├── dto/                              # AppealRequest, OperatorDecision, AppealStatusResponse
│   │   ├── init/HitlDatabaseInitializer.java
│   │   └── service/HitlEscalationService.java
│   │
│   ├── init/                                 # Startup orchestration
│   │   ├── DatabaseConnectionValidator.java
│   │   ├── DatabaseInitializer.java
│   │   ├── EncryptionKeyInitializer.java
│   │   ├── MemoryDatabaseInitializer.java
│   │   └── VectorSearchIndexInitializer.java
│   │
│   ├── payment/                              # Payment channel routing
│   │   ├── PaymentContext.java
│   │   ├── PaymentResult.java
│   │   ├── PaymentSwitch.java
│   │   ├── PaymentSwitchRouter.java
│   │   └── channels/                         # Channel implementations
│   │
│   ├── rag/                                  # Knowledge retrieval
│   │   ├── init/RagKnowledgeSeeder.java      # Seeds 7 RBI channel docs on startup
│   │   └── service/RagService.java           # embed → Atlas $vectorSearch → rerank
│   │
│   └── service/                              # Shared services
│       ├── AccountBalanceService.java
│       ├── FraudContextService.java          # Behavioural telemetry scoring
│       ├── MongoLedgerService.java           # ACID ledger commit
│       ├── PaymentMethodRecommendationService.java
│       └── TemporalMemoryService.java        # Per-session vector memory archive
│
├── src/main/resources/
│   ├── application.properties               # All values via ${ENV_VAR:default}
│   ├── encryption-schemas/                  # MongoDB Queryable Encryption field maps
│   │   ├── transactions.json
│   │   └── user_profiles.json
│   └── rag/                                 # RBI payment channel knowledge base
│       ├── cash-payment-channel.txt
│       ├── cheque-payment-channel.txt
│       ├── indian-payment-regulatory-framework.txt
│       ├── neft-payment-channel.txt
│       ├── payment-routing-risk-matrix.txt
│       ├── rtgs-payment-channel.txt
│       └── upi-payment-channel.txt
│
├── agent-ui/                                # Next.js frontend
│   └── src/
│       ├── app/
│       │   ├── page.tsx                     # Root → AgentChatDashboard
│       │   └── api/v1/
│       │       ├── agent/orchestrate-stream/route.ts   # SSE proxy (no buffering)
│       │       └── [...path]/route.ts                  # Generic reverse proxy
│       └── components/
│           ├── AgentChatDashboard.tsx        # Streaming chat + channel badge + token metrics
│           ├── UserBalanceDashboard.tsx      # Account summary, masked PII + click-to-reveal
│           ├── HitlAppealButton.tsx          # User appeal modal
│           ├── HitlOperatorDashboard.tsx     # Operator escalation panel
│           ├── Sidebar.tsx
│           └── InputBar.tsx
│
├── docker-compose.yaml
├── Dockerfile                               # api-gateway (JRE Alpine)
├── Dockerfile.ollama                        # Ollama with model pre-pull
├── Makefile
├── pom.xml
├── .env.example                             # Template — copy to .env and fill in keys
└── LICENSE
```

---

## End-to-End Request Flow

### 1. Streaming chat request

```
Browser
  │  POST /api/v1/agent/orchestrate-stream  (SSE)
  ▼
Next.js proxy  (orchestrate-stream/route.ts)
  │  Pipes ReadableStream; no buffering; --request-timeout=0
  ▼
PaSSController  (virtual thread)
  │  Fires heartbeat every 15 s to keep connection alive
  ▼
PaSSOrchestratorAgent.orchestrateSwitchStreaming()
  ▼
ContextEnricher.buildEnrichedIntent()
  ├─ VoyageAiEmbeddingModelImpl  →  Atlas $vectorSearch (rag_knowledge)
  ├─ VoyageAiScoringModel        →  rerank top-2 chunks
  ├─ Extract [APPROVED CHANNELS] from returned chunks
  ├─ TemporalMemoryService       →  vector-recall 2 prior turns
  └─ Returns enriched prompt:
       [RELEVANT KNOWLEDGE] … RAG chunks …
       [APPROVED CHANNELS] UPI, NEFT
       [CONVERSATION HISTORY] … prior turns …
       [CURRENT REQUEST] … user message …
  ▼
LangChain4j StreamingSupervisor  →  Ollama Qwen 2.5 3B (HTTP chunked)
  │  Tokens stream back via onPartialResponse → SSE chunk events
  │
  │  (if LLM decides a tool is needed)
  ▼
LedgerTools.transferFunds() / receiveFunds() / switchMandate()
  ├─ FraudContextService  — behavioural telemetry score
  └─ MongoLedgerService   — ACID transaction commit
  ▼
Second LLM call — natural language confirmation tokens stream to browser
  ▼
onCompleteResponse
  ├─ AuditLoggingService.logChatTurn()  (async virtual thread)
  └─ TemporalMemoryService.archiveTurn()  (async virtual thread)
```

### 2. RAG-constrained channel selection

```
User: "pay ₹50,000 to Ramesh"
         │
         ▼
  VectorSearch  →  retrieves NEFT + RTGS channel docs (top-2 reranked)
         │
         ▼
  [APPROVED CHANNELS]  →  "NEFT, RTGS"
         │
         ▼
  System prompt rule 4:
    "channel MUST be one of the channels listed in [APPROVED CHANNELS]"
         │
         ▼
  LLM selects NEFT  →  transferFunds("Ramesh", "NEFT", 50000)
         │
         ▼
  MongoLedgerService commits:  targetBank="NEFT", status="SETTLED"
```

### 3. HITL escalation flow

```
User clicks "Appeal"
  │  POST /api/v1/agent/appeal  {sessionId, appealReason}
  ▼
AppealController → HitlEscalationService.freezeStateAndEscalate()
  │  Creates HitlEscalationRecord  status=PENDING_HUMAN_REVIEW
  │  Writes to pass_hitl DB
  ▼
Operator dashboard polls GET /api/v1/operator/escalations/pending
  ▼
Operator reviews → POST .../approve | deny | override | cancel
  │  resolveEscalation() → updates status, logs operatorId + notes
  ▼
Immutable audit record written to pass_audit DB
```

---

## MongoDB Database Layout

| Database | Collections | Purpose |
|----------|-------------|---------|
| `pass_main` | `user_profiles`, `transactions`, `agent_reasoning` | Core ledger and user data |
| `pass_audit` | `system_audit_logs` | Immutable compliance log |
| `pass_hitl` | `hitl_escalations` | Escalation records and operator decisions |
| `pass_memory` | `chat_memory`, `temporal_memory`, `rag_knowledge` | LangChain4j session memory + RAG corpus |

Vector indexes exist on `temporal_memory` (behavioural recall) and `rag_knowledge` (knowledge retrieval).

---

## Quick Start

### Prerequisites

- Docker & Docker Compose
- **Voyage AI API key** — free tier at [voyageai.com](https://voyageai.com)

### 1. Configure secrets

```bash
cp .env.example .env
# Edit .env — set VOYAGE_API_KEY and MongoDB credentials
```

### 2. Start all services

```bash
docker compose up -d
# First run: Ollama will pull the LLM model (~2 GB, allow 10-15 min)
docker logs -f ollama   # watch until model is ready
```

> **Timezone:** All containers default to `Asia/Kolkata` (`TZ=Asia/Kolkata` in `docker-compose.yaml`). To match a different host timezone, update the `TZ` environment variable in every service to the appropriate [IANA timezone name](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones) (e.g. `America/New_York`, `Europe/London`).
> Linux hosts can instead mount `/etc/localtime:/etc/localtime:ro` and `/etc/timezone:/etc/timezone:ro` for automatic detection — macOS does not provide `/etc/timezone`, so the explicit `TZ` variable is used here.

### 3. Verify

```bash
curl http://localhost:8080/api/v1/agent/health   # {"status":"UP","toolsAvailable":true,...}
curl http://localhost:11434/api/tags             # lists loaded model
```

> `toolsAvailable: true` confirms all three `@Tool` methods (`transferFunds`, `receiveFunds`, `switchMandate`) are wired and ready.

Open **http://localhost:3000** — the chat UI is ready.

### Rebuild after code changes

```bash
# Backend only
mvn -q package -DskipTests && docker compose up -d --build api-gateway

# Frontend only
cd agent-ui && docker build --no-cache -t ai-native-payments:agent-ui-latest . \
  && docker compose -f ../docker-compose.yaml up -d agent-ui

# Both
mvn -q package -DskipTests \
  && docker compose up -d --build api-gateway \
  && cd agent-ui && docker build --no-cache -t ai-native-payments:agent-ui-latest . \
  && docker compose -f ../docker-compose.yaml up -d agent-ui
```

---

## Key API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/agent/orchestrate-stream` | Streaming SSE chat (primary) |
| `POST` | `/api/v1/agent/orchestrate` | Synchronous chat |
| `GET` | `/api/v1/account/dashboard?userId=` | Balance + recent transactions (PII masked) |
| `GET` | `/api/v1/account/reveal-pii?userId=&field=` | Server-side QE decrypt for `email` or `phone` |
| `POST` | `/api/v1/account/topup` | Direct credit (non-chat) |
| `POST` | `/api/v1/agent/appeal` | User HITL appeal |
| `GET` | `/api/v1/operator/escalations/pending` | Operator: list open cases |
| `POST` | `/api/v1/operator/escalations/{id}/approve` | Operator: approve |
| `POST` | `/api/v1/operator/escalations/{id}/deny` | Operator: deny |
| `POST` | `/api/v1/operator/escalations/{id}/override` | Operator: manual override |
| `GET` | `/api/v1/agent/health` | Health check — returns `status`, `backend`, `toolsAvailable` |

---

## Environment Variables

All secrets live in `.env` (git-ignored). See `.env.example` for the full list with descriptions. Key variables:

| Variable | Description |
|----------|-------------|
| `VOYAGE_API_KEY` | Voyage AI key for embeddings + reranking |
| `LLM_MODEL_NAME` | Ollama model name (default: `qwen2.5:latest`) |
| `MONGODB_INITDB_ROOT_USERNAME` / `_PASSWORD` | MongoDB credentials |
| `CORS_ALLOWED_ORIGINS` | Comma-separated frontend origins |
| `LLM_TIMEOUT_SECONDS` | LLM inference timeout — set high on slow hardware (default: `900`) |
| `LLM_NUM_CTX` | Ollama context window in tokens (default: `8192`; increase to `16384` for complex multi-turn sessions) |
| `MONGODB_QE_CRYPT_SHARED_LIB_PATH` | Optional path to `mongo_crypt_v1` shared library for Queryable Encryption |
| `QE_IT_CRYPT_SHARED_LIB_PATH` | Build/test helper: source library file to bundle into `target/qe-native` |
| `QE_IT_CRYPT_SHARED_LIB_URL` | Build helper: download URL for `mongo_crypt_v1` archive or binary |
| `QE_IT_CRYPT_SHARED_LIB_SHA256` | Optional checksum validation for downloaded artifact |

### Bundle QE Crypt Shared Library With Package

If your host has a `mongo_crypt_v1` library file, you can bundle it into the package:

```bash
QE_IT_CRYPT_SHARED_LIB_PATH=/absolute/path/to/mongo_crypt_v1.dylib mvn clean package -DskipTests
```

This copies the library to `target/qe-native/mongo_crypt_v1.*` during `prepare-package`,
and the Docker image includes `/app/qe-native`. The entrypoint auto-wires
`MONGODB_QE_CRYPT_SHARED_LIB_PATH` if a bundled library is present.

For portability across machines, you can commit libraries under:

`src/main/resources/qe-native/`

When no explicit path or URL is provided, the package step now uses that folder as a default source.

If you want the package step to download the library:

```bash
QE_IT_CRYPT_SHARED_LIB_URL=https://your-artifact-host/path/to/mongo_crypt_v1-linux-aarch64.tgz \
QE_IT_CRYPT_SHARED_LIB_SHA256=<optional_sha256> \
mvn clean package -DskipTests
```

To download directly into source resources for portability:

```bash
MONGODB_QE_CRYPT_SHARED_LIB_URL=https://<your-artifact-host>/mongo_crypt_v1-linux-aarch64.tgz \
MONGODB_QE_CRYPT_SHARED_LIB_SHA256=<optional_sha256> \
make qe-download-lib
```

This installs the binary under `src/main/resources/qe-native/mongo_crypt_v1.so`.

---

## Sample Test Queries

Three representative queries that exercise the **programmatic filter builder** and **LLM intent classifier**:

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

**Expected:** Classifier returns `QUERY_TRANSACTIONS` with an empty filter `{}`. All transactions are fetched and the response includes a debit/credit/net summary (e.g. *Debits: 5 × ₹20,650.00 · Credits: 2 × ₹10,020.00 · Net: ₹−10,630.00*).

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

**Expected:** Classifier returns `QUERY_TRANSACTIONS`. The programmatic filter builder produces `{"instructionType":"PASS_MONEY_TRANSFER","paymentMethod":"UPI"}` — only UPI debit transactions are returned.

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

**Expected:** Classifier returns `QUERY_SEARCH` with `BENEFICIARY: Priya Sharma`. All transactions with the user are fetched and filtered client-side by counterparty name.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `api-gateway` unhealthy | `docker logs api-gateway` — usually a missing `VOYAGE_API_KEY` |
| `Error in input stream` | Ollama took > Node.js request timeout — the Next.js proxy uses `--request-timeout=0` and the backend sends SSE heartbeats every 15 s to keep the connection alive |
| Slow inference | `qwen2.5:3b` is fastest for CPU-only; set `LLM_MODEL_NAME=qwen2.5:3b` in `.env` |
| MongoDB fails | Check `MONGODB_INITDB_ROOT_USERNAME` / `_PASSWORD` match across all URIs in `.env` |
| Ollama stuck on pull | `docker logs -f ollama`; first pull is 1-2 GB |
| Channel badge missing | Means RAG returned no channel-specific docs for that query — check `VOYAGE_API_KEY` is set |

---

## Responsible AI Design

| Principle | Implementation |
|-----------|---------------|
| **Explainability** | Every tool call and channel decision is logged in `pass_audit` with the LLM's reasoning |
| **Human agency** | HITL appeal available on every decision; operators can approve, deny, or override |
| **PII protection** | Dashboard serves `email`/`phone` masked by default (`ar•••@domain.com`, `+91••••••3210`); plaintext decoded server-side via `GET /reveal-pii` on explicit user request, with `[PII-REVEAL]` audit log entry |
| **Privacy** | PII sanitised before reaching the LLM; MongoDB Queryable Encryption on sensitive fields |
| **Fairness** | Routing is driven by RBI-published channel rules (RAG), not proprietary scoring |
| **Transparency** | Channel badge in UI shows which payment rail the LLM chose and why (via RAG+LLM label) |

---

## Contributing

1. Add new banking operations as `@Tool` methods in `LedgerTools.java`; add a matching `commit*Atomic` method in `MongoLedgerService.java` so every tool invocation is persisted to the ledger.
2. Extend the RAG corpus by adding `.txt` files to `src/main/resources/rag/` and registering the document ID in `RagKnowledgeSeeder.java`.
3. Add new payment channels: implement `PaymentSwitch`, register aliases in `PaymentSwitchRouter`, add the canonical name to `CHANNEL_SYNONYMS` in `ContextEnricher.java`.
4. Implement Spring Security for production authentication.

---

## License

MIT — see [LICENSE](LICENSE).
