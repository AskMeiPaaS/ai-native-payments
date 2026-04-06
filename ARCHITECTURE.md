# 🏛️ System Architecture: ai-native-payments

A comprehensive guide to the AI-native financial orchestration platform built for RBI PaSS (Payments Switching Service).

---

## 📑 Table of Contents

1. **Modular Package Layout** - Responsibility-based module map
2. **Core AI-Native Components** - Multi-agent orchestration, LangChain4j, tools
3. **Container & Microservices** - Docker topology, service configuration
4. **Data Flows, Streaming & Audit** - transaction processing, SSE lifecycle, session correlation
5. **Semantic Memory Schemas** - MongoDB collections, embeddings, vector search
6. **Responsible AI & PAIR** - privacy, fairness, explainability alignment
7. **Human-in-the-Loop (HITL)** - user appeals, operator dashboard, escalation APIs
8. **Performance & Resources** - connection pooling, timeouts, virtual threads
9. **Build & Deployment** - Maven, Docker, containerization
10. **Testing & Quality** - unit tests and modular feature coverage
11. **Troubleshooting** - common errors, degraded-mode behavior, and fixes

---

## 1. Modular Package Layout

The backend follows a responsibility-first package structure to keep the application easier to evolve and document:

| Package | Module Responsibility |
|---------|-----------------------|
| `com.ayedata.ai` | Supervisor agent, streaming orchestration, tools, context enrichment |
| `com.ayedata.audit` | Long-term session audit trail, API capture, exception auditing, indexes |
| `com.ayedata.config` | Mongo, Ollama, Voyage AI, HTTP client, and application wiring |
| `com.ayedata.controller` | Shared sync/streaming APIs that are not feature-specific |
| `com.ayedata.domain` | Payment and user models |
| `com.ayedata.hitl` | User appeals, operator workflows, escalation models, HITL database init |
| `com.ayedata.init` | Cross-cutting bootstrap logic and infrastructure initialization |
| `com.ayedata.rag` | Knowledge seeding, retrieval, reranking, and vector-backed context services |
| `com.ayedata.service` | Shared memory and ledger coordination services |

This keeps compliance, AI orchestration, and runtime concerns separated without changing the public API surface.

---

## 2. Core AI-Native Components

### Multi-Agent Orchestrator (LangChain4j / Java 21)

The platform is governed by a triad of LLM-powered agents working in concert:

| Agent | Role | Responsibility |
|-------|------|-----------------|
| **Supervisor** | Entry Point | Parses user intent, delegates tasks, synthesizes responses |
| **Context/Fraud Agent** | Security | Validates behavioral vectors against trusted baselines |
| **Ledger Agent** | Execution | Calls Java `@Tool` methods for ACID transactions |

LangChain4j is the central nervous system - it replaces traditional hardcoded logic with fluid, LLM-driven orchestration.

#### A. Agentic Orchestration Architecture

Instead of a standard Java Controller dictating steps, the Supervisor Agent acts as the entry point:

- Uses System Messages to understand RBI PaSS mandate rules
- Dynamically decides whether to validate behavior, execute a switch, or escalate to HITL
- Manages conversation state in MongoDB

```java
@Component
public class PaSSOrchestratorAgent {
    @PostConstruct
    void createSupervisor() {
        // Create singleton supervisor (fixed 99% memory leak from per-request creation)
        supervisor = AiServices.builder()
            .chatLanguageModel(chatModel)
            .tools(this)
            .systemMessage("You are the PaSS orchestrator...")
            .build();
    }
}
```

#### B. Tool-Driven Execution Pattern

LangChain4j bridges creative LLM reasoning with deterministic banking operations:

- `@Tool` annotations expose secure Java methods to the LLM
- LLM identifies intent and calls the Java tool (doesn't guess)
- ACID transactions ensure consistency

```java
@Component
public class PaSSExecutionTools {
    @Tool("Execute mandate switch only after Context Agent confirms vector similarity > 0.95")
    public String executePaSSSwitch(String sessionId, String merchantId, String targetBank) {
        // Strict, deterministic ACID execution
        return mongoService.commitSwitchAtomic(sessionId, merchantId, targetBank);
    }
}
```

#### C. Semantic Memory Integration

LangChain4j manages the platform's "brain":

- **Long-Term Memory:** Embedding models turn behavioral data into vectors stored in MongoDB
- **Contextual Retrieval:** Context Agent uses MongoDB Vector Search for behavioral validation
- **Short-Term Memory:** Time-Series collection stores real-time interaction data

---

## 2. Container & Microservices Architecture

### Service Topology

```
┌─────────────────────────────────────┐
│   API Gateway (Java/LangChain4j)    │
│   Port 8080                         │
└──────────┬──────────────────────────┘
           │
    ┌──────▼────────────┐
    │      Ollama       │
    │    qwen2.5:3b     │
    │      11434        │
    └───────────────────┘
           │
    ┌──────▼─────────────────┐
    │   MongoDB Atlas Local  │
    │ main/audit/hitl/memory │
    │        27017           │
    └────────────────────────┘

External APIs (Cloud):
- Voyage AI: https://api.voyageai.com/v1/embed
- Voyage AI: https://api.voyageai.com/v1/rerank
```

### Microservices Configuration

| Service | Docker Image | Port | Purpose |
|---------|--------------|------|---------|
| **api-gateway** | Custom Java JAR | 8080 | Multi-agent orchestration, streaming SSE, audit capture |
| **ollama** | ollama/ollama:latest | 11434 | Local Qwen 2.5 3B reasoning and token streaming |
| **mongodb-atlas** | mongodb-atlas-local:latest | 27017 | Semantic memory, HITL state, and audit persistence |

### Network Communication

**Local (Docker internal network):**
```
api-gateway → ollama:11434              (LLM requests)
api-gateway → mongodb-atlas:27017       (Data operations)
```

**External APIs (via Internet):**
- Voyage AI Embeddings: https://api.voyageai.com/v1/embed
- Voyage AI Reranker: https://api.voyageai.com/v1/rerank

### MongoDB Local Atlas Configuration

**Credentials & Authentication:**
- Image: `mongodb-atlas-local:latest` (supports automatic root user creation)
- Automatic Setup: Root credentials are created on first startup from environment variables
- Connection String: `mongodb://admin:mongoadmin123@mongodb-atlas:27017/<database>?authSource=admin`
- Databases: `pass_main` (primary), `pass_audit` (audit logs), `pass_hitl` (escalations), `pass_memory` (session chat memory)

**Key Environment Variables (set in .env):**
```
MONGODB_INITDB_ROOT_USERNAME=admin
MONGODB_INITDB_ROOT_PASSWORD=<set-secure-password-in-env>
```

These are automatically honored by the `mongodb-atlas-local` image on container startup.
```
api-gateway → https://api.voyageai.com/v1/embed      (Embedding requests)
api-gateway → https://api.voyageai.com/v1/rerank     (Reranking requests)
```

---

## 3. Docker Network Configuration

### Container URLs

**Local Services (Docker Internal Network):**

| Service | Hostname | Port | Internal URL | Purpose |
|---------|----------|------|--------------|---------|
| API Gateway | api-gateway | 8080 | http://api-gateway:8080 | Orchestrator |
| MongoDB Local Atlas | mongodb-atlas | 27017 | mongodb://admin:mongoadmin123@mongodb-atlas:27017/pass_main?authSource=admin | Database |
| Ollama | ollama | 11434 | http://ollama:11434 | LLM |

**External APIs (Cloud):**

| Service | Endpoint | Purpose |
|---------|----------|---------|
| Voyage Embedding | https://api.voyageai.com/v1/embed | Text embeddings (requires API key) |
| Voyage Reranker | https://api.voyageai.com/v1/rerank | Relevance scoring (requires API key) |

**Note:** MongoDB accessible from host: `mongodb://localhost:27017`

### Environment Variables

```yaml
# MongoDB
PRIMARY_MONGO_URI=mongodb://admin:mongoadmin123@mongodb-atlas:27017/pass_main?authSource=admin
AUDIT_MONGO_URI=mongodb://admin:mongoadmin123@mongodb-atlas:27017/pass_audit?authSource=admin

# Voyage AI (Cloud APIs)
VOYAGE_API_KEY=<your-api-key-here>  # Required for embeddings and reranking
EMBEDDING_MODEL_NAME=voyage-4       # 1024-dimensional embeddings
RERANKER_MODEL_NAME=rerank-lite-1   # Lightweight reranker

# Local LLM (Ollama)
LLM_BASE_URL=http://ollama:11434
LLM_MODEL_NAME=qwen2.5:3b
LLM_TIMEOUT_SECONDS=600
LLM_NUM_CTX=4096
LLM_NUM_PREDICT=1024

# Retrieval controls
RERANKER_TOP_K=2

# HTTP Timeouts
HTTP_CONNECT_TIMEOUT=10
HTTP_REQUEST_TIMEOUT=600
```

---

## 4. Data Flows & Interaction Paths

### Flow 1: Behavioral Telemetry Ingestion

Next.js UI streams device metrics into MongoDB without blocking user interaction:

```mermaid
sequenceDiagram
    participant Client as Next.js UI (Sensors)
    participant Backend as Java Backend
    participant TS_DB as MongoDB (Time-Series)
    participant AI as Voyage AI API
    participant Profile_DB as MongoDB (Profiles)

    Client->>Backend: Stream keystrokes, gyro, CPU metrics
    Backend->>TS_DB: Insert Time-Series data (async)
    
    loop Every 24 Hours
        Backend->>TS_DB: Aggregate interaction data
        Backend->>AI: Generate embedding vector (voyage-4)
        AI-->>Backend: 1024-dimensional vector
        Backend->>Profile_DB: Update baseline_vector
    end
```

### Flow 2: Autonomous PaSS Switch Orchestration

User requests mandate switch → Multi-agent system validates → Java tool executes:

```mermaid
sequenceDiagram
    actor User
    participant UI as Next.js UI
    participant Supervisor as Supervisor Agent
    participant Context as Context Agent
    participant Ledger as Ledger Agent
    participant Tool as Java @Tool
    participant Memory as MongoDB

    User->>UI: "Switch my BESCOM bill to ICICI"
    UI->>Supervisor: Intent + Telemetry Vector
    
    rect rgb(240, 245, 255)
        Supervisor->>Context: Verify session context
        Context->>Memory: Vector search behavioral baseline
        Memory-->>Context: Similarity score 0.98
        Context-->>Supervisor: Safe to proceed
        
        Supervisor->>Ledger: Map merchant + execute
        Ledger->>Memory: Search merchant directory
        Memory-->>Ledger: MERCH_BESCOM_01
    end
    
    rect rgb(235, 250, 235)
        Ledger->>Tool: executePaSSSwitch(sessionId, 'MERCH_BESCOM_01', 'ICICI')
        Tool->>Memory: Multi-doc ACID transaction
        Memory-->>Tool: Success
    end
    
    Tool-->>Supervisor: Confirmed
    Supervisor->>UI: Stream XAI confirmation
```

### Flow 3: Fraud Detection & HITL Escalation

Low similarity score + failed biometric → Graceful handoff to human analyst:

```mermaid
sequenceDiagram
    participant UI as Next.js UI
    participant Agent as Context Agent
    participant VSearch as MongoDB Vector Search
    participant Audit as Audit Logs
    participant Human as Human Analyst

    UI->>Agent: High-value switch + telemetry
    Agent->>VSearch: Compare to baseline
    VSearch-->>Agent: Similarity 0.65 (LOW)
    
    Agent->>Audit: Log AI reasoning (XAI)
    Agent-->>UI: Request biometric (FaceID)
    UI->>Agent: Biometric proof
    
    alt Proof Valid
        Agent->>Agent: Continue workflow
    else Proof Fails
        Agent->>Audit: Log HITL escalation
        Agent-->>UI: Transferring to analyst
        Agent-->>Human: Sanitized context + reasoning logs
    end
```

### Flow 4: RAG-Guided Channel Selection By The LLM

The orchestration flow uses RAG (Retrieval-Augmented Generation) to retrieve payment-routing guidance. The LLM selects the payment channel during tool invocation, and the backend persists that choice without applying deterministic selection code:

```mermaid
sequenceDiagram
  participant Agent as PaSS Orchestrator Agent
    participant RAG as RagService
    participant Knowledge as RAG Knowledge Base
  participant Tools as LedgerTools
    participant Store as MongoDB
    participant Audit as Audit Logs

  Agent->>RAG: Retrieve payment-routing context for ₹50000 transfer
    rect rgb(255, 250, 240)
        RAG->>Knowledge: Fetch "payment-routing-risk-matrix.txt"
    Knowledge-->>RAG: Routing guidance + regulatory notes
    RAG-->>Agent: Compact relevant knowledge
    end
    
    rect rgb(240, 250, 245)
    Agent->>Agent: Choose channel from retrieved knowledge
    Agent->>Tools: transferFunds("merchant", "NEFT", 50000)
    end
    
  Tools->>Store: commitSwitchAtomic(paymentMethod="NEFT", requiresHitlReview=false)
    Store-->>Tools: Transaction recorded with payment metadata
    
  Tools->>Audit: Log tool invocation and stored channel
    Audit-->>Audit: Audit trail with justification
```

**Current Runtime Characteristics:**

- The backend does not map amount ranges to payment channels with `if/else` or `switch` statements.
- The backend does not downgrade or override a channel after the LLM chooses it.
- The stored `paymentMethod` is the channel supplied by the tool call.

**RAG Knowledge Sources:**

The RAG layer can provide guidance from documents such as:

1. **payment-routing-risk-matrix.txt** — RBI-compliant routing rules by risk score
2. **upi-payment-channel.txt** — UPI Lite/UPI limits, speed, use cases
3. **neft-payment-channel.txt** — NEFT availability, daily limits, regulatory guardrails
4. **rtgs-payment-channel.txt** — RTGS requirements and settlement behavior

The important architectural constraint is that this retrieved knowledge informs the LLM. Java persistence code records the chosen channel but does not re-run channel-selection logic.

### Flow 5: End-to-End Streaming Response Lifecycle

The production chat path is fully streaming. The browser does not wait for a single aggregated payload; each token is relayed from Ollama through the backend and the Next.js runtime proxy as soon as it is available.

```mermaid
sequenceDiagram
    actor User
    participant Browser as Browser UI
    participant Proxy as Next.js Runtime Proxy
    participant Controller as PaSSController
    participant Agent as PaSSOrchestratorAgent
    participant LLM as OllamaStreamingChatModel
    participant Audit as AuditLoggingService

    User->>Browser: Submit prompt / switch request
    Browser->>Proxy: POST /api/v1/agent/orchestrate-stream
    Proxy->>Controller: Forward request + sessionId
    Controller->>Agent: orchestrateSwitchStreaming(request)
    Agent->>LLM: Generate TokenStream

    LLM-->>Agent: onPartialResponse(token)
    Agent-->>Controller: chunk text
    Controller-->>Proxy: SSE event: chunk
    Proxy-->>Browser: text/event-stream
    Browser->>Browser: Incrementally render response bubble

    LLM-->>Agent: onComplete(final response)
    Controller->>Audit: logChatTurn(sessionId, ...)
    Controller-->>Browser: SSE event: complete
```

**Streaming contract**
- `start` — emitter created and request accepted.
- `chunk` — incremental text delta from LangChain4j `TokenStream`.
- `complete` — final metadata including status, latency, and `sessionId`.
- `error` — terminal or recoverable failure signal so the UI can fall back cleanly.

**Implementation details**
- `PaSSController` owns `SseEmitter` lifecycle callbacks for timeout, completion, and disconnect handling.
- `agent-ui/src/app/api/v1/agent/orchestrate-stream/route.ts` exists specifically to prevent buffering from static rewrites.
- `AgentChatDashboard.tsx` parses raw SSE frames and keeps response text separate from metadata badges.
- A shared `sessionId` is propagated across streaming, sync calls, appeals, and audit records.

### Audit Correlation & Compliance Pipeline

The audit design is isolated from core business execution. Important API exchanges, chat turns, escalations, and uncaught exceptions are written to `pass_audit` with a common `sessionId`, timestamps, and actor metadata.

```mermaid
flowchart LR
    UI[Next.js UI] -->|HTTP or SSE with sessionId| API[Spring Controllers]
    API --> FILTER[ApiAuditLoggingFilter]
    API --> CHAT[AuditLoggingService.logChatTurn]
    API --> HITL[HITL escalation audit]
    FILTER --> AUDITDB[(pass_audit.system_audit_logs)]
    CHAT --> AUDITDB
    HITL --> AUDITDB
    EX[GlobalAuditExceptionHandler] --> AUDITDB
```

| Event Type | Source | What it captures |
|---|---|---|
| `CHAT_TURN` | `PaSSController` | prompt summary, response summary, latency, sessionId |
| `API_EXCHANGE` | `ApiAuditLoggingFilter` | request/response payloads for `/api/v1/**` |
| `USER_APPEAL` / `HITL_ESCALATION` | HITL services | freeze-state transitions and operator actions |
| `ERROR` | `GlobalAuditExceptionHandler` | structured failure evidence with correlation IDs |

**Audit mechanics**
- `AuditIndexInitializer` creates indexes on `sessionId`, `eventType`, and `traceId` for lookup efficiency.
- Payloads are truncated before persistence to control document size while preserving debugging value.
- Expected SSE client disconnects are treated as normal transport edge cases instead of compliance incidents.

### Error Handling & Degradation Strategy

The platform is designed to fail safely. Deterministic payment tools remain authoritative, while AI and retrieval components can degrade without corrupting ledger state.

| Failure Source | Detection Point | Runtime Behavior | Audit Action |
|---|---|---|---|
| Ollama timeout or slow generation | LLM timeout callbacks | return partial stream or friendly fallback; never force a ledger write | record `ERROR` with model and latency |
| Browser disconnect during SSE | `SseEmitter.onCompletion/onError` | stop streaming cleanly and suppress noisy stack traces | warning only when relevant |
| RAG or reranker unavailable | `RagService.retrieveContext()` | continue with compact base prompt and no retrieved snippets | log degraded enrichment event |
| HITL fetch returns no escalations | operator dashboard fetch path | render empty-state `[]`, not an error banner | no compliance event required |
| Unexpected controller exception | `GlobalAuditExceptionHandler` | structured JSON error response with safe message | persist session-correlated error record |

```mermaid
flowchart TD
    REQ[Incoming request] --> VALIDATE{Valid payload?}
    VALIDATE -- No --> BADREQ[Return 4xx JSON error]
    VALIDATE -- Yes --> STREAM{Streaming path?}
    STREAM -- Yes --> LLM[Generate via TokenStream]
    STREAM -- No --> SYNC[Generate sync response]

    LLM --> TOKEN{Chunk or error?}
    TOKEN -- Chunk --> SSE[Emit SSE chunk]
    TOKEN -- Error --> FALLBACK[Emit error event and complete safely]

    SYNC --> RESULT{Tool or model failure?}
    RESULT -- No --> RESP[Return response]
    RESULT -- Yes --> HANDLER[GlobalAuditExceptionHandler]

    FALLBACK --> AUDIT[(pass_audit)]
    HANDLER --> AUDIT
    RESP --> AUDIT
```

---

## 5. Semantic Memory Schema

### A. Long-Term Memory (user_profiles)

Behavioral baseline for Context Agent validation. **PII fields encrypted with Queryable Encryption:**

```json
{
  "_id": "USR_998877",
  "email": "<QE_ENCRYPTED_CIPHERTEXT>",  // Deterministic encryption for queries
  "phone": "<QE_ENCRYPTED_CIPHERTEXT>",
  "bank_account": "<QE_ENCRYPTED_CIPHERTEXT>",
  "behavioral_fingerprint": {
    "baseline_vector": [0.102, -0.441, 0.882, ...],
    "trusted_devices": ["DEV_HASH_A1B2", "DEV_HASH_C3D4"],
    "typing_pattern": {
      "avg_flight_time_ms": 110,
      "std_dev_flight_time": 15
    },
    "last_learned_at": ISODate("2026-03-29T10:00:00Z")
  },
  "__safeContentIndicators": {
    "has_email": true,
    "has_ssn": false
  }
}
```

**Note:** Behavioral embeddings (`baseline_vector`) remain unencrypted (non-PII). Query operations on encrypted fields use deterministic encryption for consistency.

### B. Short-Term Memory (interaction_logs - Time-Series)

Real-time context window for Supervisor Agent:

```json
{
  "timestamp": ISODate("2026-03-29T21:24:58Z"),
  "metadata": {
    "session_id": "SESS_12345",
    "user_id": "USR_998877"
  },
  "device_context": {
    "device_hash": "DEV_HASH_A1B2",
    "battery_level": 82.4,
    "network_latency_ms": 35
  },
  "interaction_event": {
    "flight_time_ms": 110,
    "dwell_time_ms": 45
  }
}
```

### C. Deterministic Ledger (transactions)

Result of agent tool execution with XAI snapshot. **Financial account identifiers encrypted:**

```json
{
  "_id": "TXN_PASS_55443322",
  "instruction_type": "PASS_MANDATE_SWITCH",
  "status": "SETTLED",
  "created_at": ISODate("2026-03-29T21:25:00Z"),
  "financial_data": {
    "amount": 15000.00,
    "donor_account": "<QE_ENCRYPTED_ACCT_1>",  // Encrypted, queryable
    "recipient_account": "<QE_ENCRYPTED_ACCT_2>",
    "donor_bank": "HDFC",
    "recipient_bank": "ICICI",
    "user_email": "<QE_ENCRYPTED_EMAIL>"  // Encrypted, queryable
  },
  "agent_reasoning_snapshot": {
    "supervisor_decision": "APPROVED",
    "context_similarity_score": 0.985,
    "llm_justification": "Session vector aligns with baseline. Merchant semantic match confirmed."
  },
  "encryption_metadata": {
    "keyIdsUsed": ["key-uuid-20260404"],
    "encryptedAt": ISODate("2026-03-29T21:25:00Z")
  }
}
```

**Encryption Strategy:**
- Amounts and bank codes remain plaintext (non-PII)
- Account numbers and email encrypted with deterministic algorithm (enables queries)
- Key rotation audit trail preserved in `encryption_metadata`

### D. Explainable AI Logs (ai_reasoning_logs)

Immutable audit trail for regulators:

```json
{
  "_id": "AUDIT_AI_990011",
  "related_transaction": "TXN_PASS_55443322",
  "event_type": "FRAUD_INTERVENTION",
  "timestamp": ISODate("2026-03-29T21:25:00Z"),
  "model_used": "qwen2.5:3b",
  "inputs": {
    "metadata_snapshot": {
      "network_latency": "400ms",
      "new_device": true,
      "location_change": "US -> IN"
    }
  },
  "outputs": {
    "decision": "TRIGGER_STEP_UP_AUTH",
    "reasoning": "New device detected from high-risk ASN"
  }
}
```

---

## 6. Responsible AI & PAIR Alignment

### Privacy by Design: MongoDB Queryable Encryption (QE)

**Queryable Encryption Implementation:**
- **Automatic Encryption:** PII fields (SSN, bank account, email) encrypted client-side before transmission
- **Server-Side Processing:** MongoDB stores ciphertext; searching on encrypted fields supported natively
- **Zero-Knowledge Architecture:** Server never sees plaintext PII; decryption happens only in trusted Java layer
- **LLMs Never See PII:** Prompts contain encrypted tokens, never plaintext financial data

**QE Key Generation:**
- Master encryption key generated in `DatabaseInitializer.java` during app startup
- Key stored in secure MongoDB KMIP provider or AWS KMS partition (production)
- DataKeyring refresh scheduled hourly for key rotation compliance
- Audit trail: `encryption_keys` collection tracks all key operations with timestamps

**Encrypted Fields (Schema):**
```json
{
  "_id": "USR_998877",
  "email": "<encrypted_ciphertext_16A...>",     // Queryable
  "phone": "<encrypted_ciphertext_2B7...>",    // Queryable
  "ssn": "<encrypted_ciphertext_8C4...>",      // Queryable
  "bank_account": "<encrypted_ciphertext_D9...>" // Queryable
}
```

**Supported Operations on Encrypted Data:**
- Equality filter: `{email: user@example.com}` - Query on encrypted field directly
- Vector search: Behavioral embeddings remain unencrypted (no PII therein)
- Aggregation: Range operators on encrypted fields NOT supported (by design)
- Integrity: HMAC over ciphertext prevents tampering

### Client-Side Encryption Configuration

**AutoEncryptionSettings in Java Client:**
```java
@Configuration
public class EncryptionConfig {
    @Bean
    public MongoClient mongoClient(DataKeyProvider dataKeyProvider) {
        AutoEncryptionSettings autoEncryptionSettings = 
            AutoEncryptionSettings.builder()
                .keyVaultMongoClientSettings(
                    MongoClientSettings.builder()
                        .applyConnectionString(
                            new ConnectionString("mongodb://...")
                        ).build()
                )
                .keyVaultDatabase("keyvault")  // Separate DB for key storage
                .keyVaultCollectionName("keys")
                .kmsProviders(Map.of(
                    "aws", Map.of(
                        "accessKeyId", System.getenv("AWS_ACCESS_KEY_ID"),
                        "secretAccessKey", System.getenv("AWS_SECRET_ACCESS_KEY")
                    )
                ))
                // Map collection to encryption schema
                .schemaMap(Map.of(
                    "pass_main.user_profiles", 
                    BsonDocument.parse(encryptionSchema)
                ))
                .build();
        
        return MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(
                    new ConnectionString("mongodb://...")
                )
                .autoEncryptionSettings(autoEncryptionSettings)
                .build()
        );
    }
}
```

**Encryption Schema (JSON):**
```json
{
  "bson": {
    "encryptMetadata": {
      "keyId": ["/keyId"],
      "algorithm": "AEAD_AES_256_CBC_HMAC_SHA_512-Random"
    },
    "properties": {
      "email": {
        "bsonType": "string",
        "encrypt": {
          "keyId": ["/keyId"],
          "algorithm": "AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic"
        }
      },
      "phone": {
        "bsonType": "string",
        "encrypt": {
          "algorithm": "AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic"
        }
      },
      "ssn": {
        "bsonType": "string",
        "encrypt": {
          "algorithm": "AEAD_AES_256_CBC_HMAC_SHA_512-Random"
        }
      },
      "bank_account": {
        "bsonType": "string",
        "encrypt": {
          "algorithm": "AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic"
        }
      }
    }
  }
}
```

**Encryption Algorithms:**
- **Deterministic:** `AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic` (queryable)
- **Random:** `AEAD_AES_256_CBC_HMAC_SHA_512-Random` (non-queryable, higher security)

### Fairness & Accessibility

- Behavioral embeddings distinguish genuine accessibility delays from bot patterns
- No penalties for users with screen readers or voice-to-text
- Graceful degradation instead of hard fraud locks

### Human Agency & Control

Every AI decision includes:
- "Appeal / Speak to Human" button in UI
- Immediate state freeze in MongoDB
- Transfer of sanitized telemetry + reasoning logs to analyst

### Explainable AI (XAI)

Complex vector scores translated to human-readable transparency:

```
Instead of: "Similarity score 0.65"
User sees: "We noticed you're logging in from a new city today. 
           Please use FaceID to proceed."
```

---

## 7. Human-in-the-Loop (HITL) Architecture

### 7.1 Transaction Reference & User Tracking

**Transaction ID Lifecycle:**

| Phase | ID Type | When Generated | For Whom | Purpose |
|-------|---------|---|----------|---------|
| **Pre-Escalation** | sessionId | At user login | Frontend | Track session through chat |
| **During HITL Review** | escalationId | On escalation trigger | User + Operator | Appeal reference number |
| **Post-Approval** | transactionId | After operator decision | User | Permanent transaction receipt |

**User-Facing Reference Flow:**
```
1. User initiates transaction → sessionId created
2. AI triggers escalation → escalationId created (e.g., "ESC_2026040412345")
   └─ User sees: "Your appeal reference: ESC_2026040412345"
3. User can track status via: GET /api/v1/agent/appeal-status/ESC_2026040412345
4. Operator approves → transactionId generated (e.g., "TXN_PASS_55443322")
   └─ User sees: "Transaction confirmed: TXN_PASS_55443322"
```

### 7.2 Escalation Triggers

**System-Initiated Escalation:**
1. Context Agent confidence score < threshold (e.g., 0.80)
2. Biometric step-up authentication fails
3. Behavioral anomaly detected + session verification timeout
4. Fraud patterns detected at high severity
5. Semantic distance exceeds acceptable bounds

**User-Initiated Escalation:**
1. User clicks "Appeal / Speak to Human" button in chat UI
2. User explicitly requests manual review
3. User disagrees with AI decision and wants operator input

### 7.3 REST API Endpoints

#### User-Facing Endpoints (Chat UI)

**POST `/api/v1/agent/appeal` - Submit User Appeal**

Request:
```json
{
  "sessionId": "user-session-123",
  "appealReason": "I believe this transaction is legitimate. I made this purchase yesterday."
}
```

Response (200 OK):
```json
{
  "message": "Your appeal has been recorded and will be reviewed by a human analyst",
  "escalationId": "ESC_2026040412345",
  "sessionId": "user-session-123",
  "status": "ESCALATED_TO_HUMAN",
  "estimatedResolutionTime": "5-10 minutes"
}
```

**GET `/api/v1/agent/appeal-status/{escalationId}` - Check Appeal Status**

Response (200 OK - Pending):
```json
{
  "escalationId": "ESC_2026040412345",
  "sessionId": "user-session-123",
  "status": "PENDING_HUMAN_REVIEW",
  "createdAt": "2026-04-04T10:30:00Z",
  "message": "Your appeal is being reviewed. Please wait..."
}
```

Response (200 OK - Resolved):
```json
{
  "escalationId": "ESC_2026040412345",
  "sessionId": "user-session-123",
  "status": "RESOLVED_APPROVED",
  "transactionId": "TXN_PASS_55443322",
  "operatorMessage": "Verified with customer. Transaction approved.",
  "resolvedAt": "2026-04-04T10:35:00Z",
  "message": "Transaction approved! Your confirmation ID is TXN_PASS_55443322"
}
```

#### Operator-Facing Endpoints (Dashboard)

**GET `/api/v1/operator/escalations/pending` - List Pending Cases**

Response (200 OK):
```json
[
  {
    "id": "escalation-abc123",
    "sessionId": "user-session-123",
    "reasoning": "User appeal: I believe this transaction is legitimate",
    "status": "PENDING_HUMAN_REVIEW",
    "appealSource": "USER_INITIATED",
    "createdAt": "2026-04-04T10:30:00Z"
  }
]
```

**GET `/api/v1/operator/escalations/{id}` - Get Escalation Details**

Response (200 OK):
```json
{
  "id": "escalation-abc123",
  "sessionId": "user-session-123",
  "reasoning": "AI reasoning: Context Agent confidence 0.65 < threshold 0.80",
  "status": "PENDING_HUMAN_REVIEW",
  "createdAt": "2026-04-04T10:30:00Z",
  "operatorId": null,
  "operatorNotes": null,
  "resolvedAt": null
}
```

**POST `/api/v1/operator/escalations/{id}/approve` - Approve Decision**

Request:
```json
{
  "operatorId": "operator-john-doe",
  "operatorNotes": "Verified with customer. Transaction is legitimate. Amount and merchant match customer profile."
}
```

Response (200 OK):
```json
{
  "id": "escalation-abc123",
  "escalationId": "ESC_2026040412345",
  "transactionId": "TXN_PASS_55443322",
  "status": "RESOLVED_APPROVED",
  "operatorId": "operator-john-doe",
  "operatorNotes": "Verified with customer. Transaction is legitimate.",
  "resolvedAt": "2026-04-04T10:35:00Z",
  "message": "Transaction executed successfully"
}
```

**POST `/api/v1/operator/escalations/{id}/deny` - Deny Decision**

Request:
```json
{
  "operatorId": "operator-jane-smith",
  "operatorNotes": "Fraud indicators present. High-risk merchant in non-registered country."
}
```

Response (200 OK):
```json
{
  "id": "escalation-abc123",
  "escalationId": "ESC_2026040412345",
  "status": "RESOLVED_DENIED",
  "operatorId": "operator-jane-smith",
  "operatorNotes": "Fraud indicators present. High-risk merchant in non-registered country.",
  "resolvedAt": "2026-04-04T10:35:00Z",
  "message": "Transaction denied. User will be notified."
}
```

**POST `/api/v1/operator/escalations/{id}/override` - Manual Override**

Request:
```json
{
  "operatorId": "operator-admin",
  "operatorNotes": "Customer called and confirmed. Executing alternate merchant ID: MERCHANT_XYZ"
}
```

Response (200 OK):
```json
{
  "id": "escalation-abc123",
  "escalationId": "ESC_2026040412345",
  "transactionId": "TXN_PASS_55443323",
  "status": "RESOLVED_MANUAL_OVERRIDE",
  "operatorId": "operator-admin",
  "operatorNotes": "Customer called and confirmed. Executing alternate merchant ID: MERCHANT_XYZ",
  "resolvedAt": "2026-04-04T10:35:00Z",
  "message": "Transaction executed with manual override"
}
```

**POST `/api/v1/operator/escalations/{id}/cancel` - Cancel Transaction**

Request:
```json
{
  "operatorId": "operator-security",
  "operatorNotes": "Confirmed as fraudulent. Customer identity stolen. Account locked for 24 hours."
}
```

Response (200 OK):
```json
{
  "id": "escalation-abc123",
  "escalationId": "ESC_2026040412345",
  "status": "RESOLVED_CANCELLED",
  "operatorId": "operator-security",
  "operatorNotes": "Confirmed as fraudulent. Customer identity stolen. Account locked for 24 hours.",
  "resolvedAt": "2026-04-04T10:35:00Z",
  "message": "Transaction cancelled and account secured"
}
```

### 7.3 Frontend Components

#### HitlAppealButton Component

**Location:** `agent-ui/src/components/HitlAppealButton.tsx`

**Features:**
- Modal dialog for submitting appeals
- 500-character limit for appeal reason
- Real-time character counter
- Loading state with spinner
- Success confirmation with auto-close
- Error handling with retry capability
- Context-aware styling (orange for appeals)

**Usage in Chat UI:**
```tsx
import HitlAppealButton from '@/components/HitlAppealButton';

export default function ChatInterface() {
  return (
    <div className="chat-container">
      <div className="message-actions">
        <HitlAppealButton 
          sessionId={currentSession.id}
          onAppealSubmitted={() => {
            refreshChatHistory();
            showNotification("Appeal submitted successfully!");
          }}
        />
      </div>
      {/* Rest of chat UI */}
    </div>
  );
}
```

#### HitlOperatorDashboard Component

**Location:** `agent-ui/src/components/HitlOperatorDashboard.tsx`

**Features:**
- Real-time escalation list with auto-refresh (10s)
- Dashboard KPIs: Pending cases, resolved today, total cases
- Expandable case details with status badges
- Action buttons: Approve, Deny, Override, Cancel
- Operator notes textarea
- Appeal source indicator (user vs. system)
- Status filtering and search
- Timestamp tracking for SLA monitoring

**Displays:**
```
Dashboard Header
├── Pending Cases Counter (Yellow)
├── Resolved Today Counter (Green)
└── Total Cases Counter (Blue)

Escalation List
├── Session ID
├── Reasoning / Appeal Reason
├── Status Badge
├── Appeal Source Badge (👤 User / 🤖 System)
├── Creation Timestamp
└── [Expandable Actions Panel]
    ├── Operator Notes Textarea
    ├── [Approve Button]
    ├── [Deny Button]
    ├── [Override Button]
    └── [Cancel Button]
```

### 7.4 Data Model: HitlEscalationRecord

**MongoDB Collection:** `hitl_escalations`

**Stored in:** Dedicated HITL database (pass_hitl)

**Schema:**
```json
{
  "_id": ObjectId,
  "escalationId": "String (e.g., 'ESC_2026040412345', indexed)",
  "sessionId": "String (indexed)",
  "transactionId": "String (null until operator approves, indexed)",
  "reasoning": "String (AI reasoning or user appeal text)",
  "status": "PENDING_HUMAN_REVIEW | RESOLVED_APPROVED | RESOLVED_DENIED | RESOLVED_MANUAL_OVERRIDE | RESOLVED_CANCELLED",
  "appealSource": "USER_INITIATED | SYSTEM_INITIATED",
  "createdAt": ISODate,
  "operatorId": "String (null until resolved)",
  "operatorNotes": "String (null until resolved)",
  "resolvedAt": ISODate(null initially)
}
```

**Indexes:**
```javascript
db.hitl_escalations.createIndex({ "escalationId": 1 });
db.hitl_escalations.createIndex({ "status": 1, "createdAt": -1 });
db.hitl_escalations.createIndex({ "sessionId": 1 });
db.hitl_escalations.createIndex({ "transactionId": 1 });
db.hitl_escalations.createIndex({ "operatorId": 1, "resolvedAt": -1 });
```

### 7.5 Service Layer: HitlEscalationService

**Location:** `src/main/java/com/ayedata/service/HitlEscalationService.java`

**Public Methods:**

| Method | Purpose | Parameters | Returns |
|--------|---------|------------|---------|
| `freezeStateAndEscalate()` | Freeze transaction on escalation | sessionId, llmReasoning | escalationId (String) |
| `getPendingEscalations()` | Fetch all pending cases for dashboard | - | List<HitlEscalationRecord> |
| `getEscalationById()` | Retrieve specific case details | escalationId | HitlEscalationRecord |
| `getEscalationBySessionId()` | Query by user session | sessionId | HitlEscalationRecord |
| `getAppealStatus()` | Check appeal status for user | escalationId | AppealStatusResponse |
| `resolveEscalation()` | Apply operator decision + generate transactionId | escalationId, decision, operatorId, notes | HitlEscalationRecord |
| `appealDecision()` | Handle user appeal submission | sessionId, appealReason | escalationId (String) |

**Example Usage:**
```java
// System-initiated escalation - returns escalationId
String escalationId = hitlEscalationService.freezeStateAndEscalate(
    "user-session-123",
    "Context Agent confidence: 0.65 < threshold: 0.80"
);
// Return to user: "Your appeal reference: ESC_2026040412345"

// User appeal - returns escalationId
String escalationId = hitlEscalationService.appealDecision(
    "user-session-123",
    "I believe this transaction is legitimate"
);

// Check appeal status
AppealStatusResponse status = hitlEscalationService.getAppealStatus(escalationId);
// Returns transactionId once operator approves

// Operator resolution - generates transactionId before returning
HitlEscalationRecord resolved = hitlEscalationService.resolveEscalation(
    "ESC_2026040412345",
    "APPROVED",
    "operator-john-doe",
    "Verified with customer. Transaction approved."
);
// Now contains: transactionId = "TXN_PASS_55443322"
```

### 7.6 Escalation Lifecycle Diagram

```
User Request
    │
    ├─ [SYSTEM PATH] ──────────────────────┐
    │  • Context Agent confidence < 0.80    │
    │  • Anomaly detection trigger          │
    │  • Fraud pattern identified           │
    │                                        │
    ├─ [USER INITIATED PATH] ───────────────┤
    │  • User clicks "Appeal / Speak Human" │
    │  • POST /api/v1/agent/appeal          │
    │                                        │
    └─────────────────┬──────────────────────┘
                      │
                      ▼
        ┌─────────────────────────────┐
        │ FREEZE STATE IN MONGODB     │
        │ • Mark as PENDING_REVIEW    │
        │ • Store AI reasoning        │
        │ • Log to audit trail        │
        └─────────────────────────────┘
                      │
                      ▼
        ┌─────────────────────────────┐
        │ OPERATOR DASHBOARD          │
        │ • Auto-refresh pending list │
        │ • Show case details         │
        │ • Display reasoning         │
        └─────────────────────────────┘
                      │
        ┌─────────────┼─────────────┬──────────────┐
        │             │             │              │
        ▼             ▼             ▼              ▼
      APPROVE       DENY        OVERRIDE       CANCEL
        │             │             │              │
        └────────┬────┴────┬────────┘              │
                 │         │                       │
                 ▼         ▼                       ▼
        ┌──────────────────────────────┐
        │ EXECUTE OPERATOR DECISION    │
        │ • Mark as RESOLVED_XXX       │
        │ • Log operator ID + notes    │
        │ • Timestamp resolution       │
        └──────────────────────────────┘
                 │
                 ▼
        ┌──────────────────────────────┐
        │ RETURN RESULT TO USER        │
        │ • Via WebSocket or polling   │
        │ • Show operator's message    │
        │ • Allow appeal if denied     │
        └──────────────────────────────┘
```

### 7.7 Request/Response Flow Sequence

```
sequenceDiagram
    participant User as User (Chat)
    participant Frontend as Next.js UI
    participant Gateway as Java API Gateway
    participant Service as HitlEscalation Service
    participant DB as MongoDB (HITL)
    participant Operator as Operator Dashboard
    participant OpService as Operator Service

    User->>Frontend: Clicks "Appeal / Speak Human"
    Frontend->>Frontend: Show appeal modal
    User->>Frontend: Enters appeal reason
    Frontend->>Gateway: POST /api/v1/agent/appeal
    Gateway->>Service: appealDecision(sessionId, reason)
    Service->>DB: Save HitlEscalationRecord
    Service->>DB: Create audit log entry
    Gateway-->>Frontend: 200 OK (escalated)
    Frontend-->>User: "Appeal submitted, awaiting review"
    
    par
        Operator->>Operator: Refreshes dashboard
        Operator->>Gateway: GET /api/v1/operator/escalations/pending
        Gateway->>Service: getPendingEscalations()
        Service->>DB: Query status=PENDING
        Service-->>Gateway: List of cases
        Gateway-->>Operator: Display in dashboard
        
        Operator->>Operator: Reviews case details
        Operator->>Operator: Enters approval notes
        Operator->>Gateway: POST /api/v1/operator/escalations/{id}/approve
        Gateway->>OpService: resolveEscalation(id, APPROVED, opId, notes)
        OpService->>DB: Update status→RESOLVED_APPROVED
        OpService->>DB: Log operatorId + timestamp
        OpService->>DB: Create audit event
        Gateway-->>Operator: 200 OK (resolved)
        Operator-->>Operator: Show success
    end
    
    Frontend->>Gateway: GET /api/v1/agent/appeal-status/{sessionId}
    Gateway-->>Frontend: RESOLVED_APPROVED
    Frontend-->>User: "Transaction approved by operator"
```

### 7.8 Status Transition Rules

**Valid State Transitions:**

```
PENDING_HUMAN_REVIEW
    │
    ├─ Operator Action: APPROVE ─────────► RESOLVED_APPROVED
    │                                       (execute transaction)
    │
    ├─ Operator Action: DENY ─────────────► RESOLVED_DENIED
    │                                       (rollback tx, notify user)
    │
    ├─ Operator Action: OVERRIDE ────────► RESOLVED_MANUAL_OVERRIDE
    │                                       (execute manual intent)
    │
    └─ Operator Action: CANCEL ─────────► RESOLVED_CANCELLED
                                          (abandon tx, log reason)
```

**Cannot transition from RESOLVED states** - immutable for audit compliance.

### 7.9 Security & Access Control

**For Production Deployment, Implement:**

1. **Operator Authentication:**
   ```java
   @PostMapping("/escalations/{id}/approve")
   @PreAuthorize("hasRole('HITL_OPERATOR')")
   public ResponseEntity<HitlEscalationRecord> approveEscalation(...) {
       // Only HITL_OPERATOR role can approve
   }
   ```

2. **Audit Logging:**
   ```java
   auditService.logAction(
       "HITL_DECISION",
       operator.getId(),
       escalation.getSessionId(),
       decision,
       timestamp
   );
   ```

3. **Rate Limiting:**
   - Max 100 appeals per sessionId per day
   - Global limit: 10,000 appeals per hour

4. **Encryption:**
   - Encrypt operatorNotes if containing sensitive data
   - Use TLS for all operator API communication

---

## 7.10 Database Initialization with Queryable Encryption

### DatabaseInitializer (Init Operation with Queryable Encryption)

**Location:** `src/main/java/com/ayedata/init/DatabaseInitializer.java`

**Purpose:** 
- Create collections and indexes
- Generate Queryable Encryption master key
- Initialize data encryption key (DEK) in key vault
- Set up time-series buckets
- Configure encryption schemas

**Executed On:** Every application startup (idempotent)

**QE Key Generation Flow:**
```java
@Component
public class DatabaseInitializer implements InitializingBean {
    
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private EncryptionService encryptionService;
    
    @Override
    public void afterPropertiesSet() throws Exception {
        initializeDatabases();
        generateQueryableEncryptionKey();
        createCollectionsAndIndexes();
        setupTimeSeriesBuckets();
    }
    
    /**
     * Generate Master Encryption Key (MEK) and Data Encryption Key (DEK)
     * MEK: Stored in AWS KMS (production) or local keystore (dev)
     * DEK: Stored in MongoDB keyvault for client-side encryption
     */
    private void generateQueryableEncryptionKey() {
        try {
            // Check if key already exists (idempotent)
            Document existingKey = mongoTemplate.getCollection("keyvault.keys")
                .find(new Document("keyType", "local"))
                .first();
            
            if (existingKey != null) {
                logger.info("Queryable Encryption key already exists. Skipping generation.");
                return;
            }
            
            // Generate 96-byte random key material
            byte[] keyMaterial = generateSecureRandomBytes(96);
            
            // Create DEK document
            Document dataEncryptionKey = new Document()
                .append("keyType", "local")
                .append("key", keyMaterial)
                .append("createdAt", new Date())
                .append("algorithm", "AEAD_AES_256_CBC_HMAC_SHA_512")
                .append("status", "ACTIVE")
                .append("keyRotationSchedule", "HOURLY");
            
            // Insert into key vault
            MongoCollection<Document> keyVault = 
                mongoTemplate.getMongoClient()
                    .getDatabase("keyvault")
                    .getCollection("keys");
            
            InsertOneResult result = keyVault.insertOne(dataEncryptionKey);
            String keyId = result.getInsertedId().asObjectId().getValue().toString();
            
            logger.info("✅ Queryable Encryption Key Generated - KeyId: {}", keyId);
            
            // Log to audit trail
            auditEncryptionEvent(
                "QE_KEY_GENERATED",
                keyId,
                "AES-256-GCM",
                "SYSTEM_INITIALIZATION"
            );
            
        } catch (Exception e) {
            logger.error("❌ Failed to initialize Queryable Encryption key", e);
            throw new RuntimeException("QE Initialization failed", e);
        }
    }
    
    private byte[] generateSecureRandomBytes(int length) {
        byte[] key = new byte[length];
        SecureRandom random = new SecureRandom();
        random.nextBytes(key);
        return key;
    }
    
    private void auditEncryptionEvent(String event, String keyId, 
                                      String algorithm, String source) {
        Document auditEntry = new Document()
            .append("event", event)
            .append("keyId", keyId)
            .append("algorithm", algorithm)
            .append("source", source)
            .append("timestamp", new Date())
            .append("status", "SUCCESS");
        
        mongoTemplate.getCollection("system_audit_logs")
            .insertOne(auditEntry);
    }
    
    private void createCollectionsAndIndexes() {
        // ... existing collection creation code ...
        
        // Create keyvault collection with schema validation
        createKeyVaultCollection();
    }
    
    private void createKeyVaultCollection() {
        MongoDatabase keyvaultDb = mongoTemplate.getMongoClient()
            .getDatabase("keyvault");
        
        if (!keyvaultDb.listCollectionNames().into(new ArrayList<>())
            .contains("keys")) {
            
            keyvaultDb.createCollection("keys");
            
            // Create indexes for efficient key lookup
            MongoCollection<Document> keyVault = keyvaultDb.getCollection("keys");
            keyVault.createIndex(new Document("status", 1));
            keyVault.createIndex(new Document("createdAt", 1));
            keyVault.createIndex(new Document("keyType", 1));
            
            logger.info("✅ Key Vault collection created and indexed");
        }
    }
}
```

### Queryable Encryption Service 

**Location:** `src/main/java/com/ayedata/service/EncryptionService.java`

**Responsibilities:**
- Query on encrypted fields (deterministic decryption)
- Handle key rotation
- Validate encryption schema compliance
- Provide decryption hooks for audit access

**Example Usage:**
```java
@Service
public class QueryableEncryptionService {
    
    /**
     * Query user by encrypted email (deterministic encryption allows this)
     */
    public UserProfile findUserByEmail(String plainEmail) {
        // MongoDB client auto-encrypts the query parameter
        // Comparison happens on encrypted side
        return mongoTemplate.findOne(
            Query.query(Criteria.where("email").is(plainEmail)),
            UserProfile.class
        );
    }
    
    /**
     * Decrypt PII for authorized audit operations
     * Called only when compliance review requires plaintext access
     */
    public String decryptFieldForAudit(String encryptedValue, String auditReason, String auditorId) {
        auditService.logFieldDecryption(
            encryptedValue, auditReason, auditorId
        );
        return mongoClient.decrypt(encryptedValue);
    }
}
```

---

## 8. Performance & Resource Management

### Connection Pool Configuration

**MongoDB Primary (pass_main):**
```properties
max_pool_size=50
min_pool_size=10
connection_timeout_ms=5000
socket_timeout_ms=30000
```

**MongoDB HITL (pass_hitl):**
```properties
max_pool_size=20
min_pool_size=5
connection_timeout_ms=5000
socket_timeout_ms=30000
```

**MongoDB Audit (pass_audit):**
```properties
max_pool_size=20
min_pool_size=5
connection_timeout_ms=5000
```

### HttpClient Configuration

**Shared Singleton with Virtual Thread Executor:**
```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
HttpClient httpClient = HttpClient.newBuilder()
    .executor(executor)
    .connectTimeout(Duration.ofSeconds(10))
    .build();
```

**Timeouts:**
- LLM requests: 30 seconds
- HTTP connect: 10 seconds
- HTTP request: 30 seconds

### Memory Leak Fixes Applied

| Issue | Root Cause | Solution |
|-------|-----------|----------|
| Unbounded HttpClient creation | Per-request new HttpClient() | Injected singleton with virtual threads |
| MongoClient not closed | Implicit lifecycle | Explicit `@PreDestroy` hooks |
| Supervisor per-request | Creating 100+ agent instances | Singleton supervisor in `@PostConstruct` |
| Stack trace bloat | Full exception traces in logs | Limited to 5 frames + wrapping |
| MongoDB pool exhaustion | Unclosed connections | Explicit connection lifecycle management |

---

## 9. Build & Deployment

### Maven Build

```bash
# Full build with tests
mvn clean package

# Skip tests (faster)
mvn clean package -DskipTests

# Run locally
java -jar target/ai-native-payments-1.0.0.jar
```

### Docker Deployment

```bash
# Build and run all services
docker-compose up --build

# Check service health
curl http://localhost:8080/actuator/health
```

### Application Properties

Configuration in `src/main/resources/application.properties`:

```properties
# Server
server.port=8080
server.servlet.max-http-post-size=10MB

# MongoDB Primary
spring.data.mongodb.primary.uri=${PRIMARY_MONGO_URI}

# MongoDB Audit
spring.data.mongodb.audit.uri=${AUDIT_MONGO_URI}

# AI Services
app.ai.embedding.base-url=${EMBEDDING_BASE_URL}
app.ai.reranker.base-url=${RERANKER_BASE_URL}
app.ai.llm.base-url=${LLM_BASE_URL}

# Timeouts
app.ai.llm.timeout.seconds=30
app.http.connect.timeout.seconds=10
app.http.request.timeout.seconds=30
```

---

## 10. Testing & Quality

### Unit Tests (24 tests - all passing)

- MongoLedgerServiceTest (11 tests)
- HitlEscalationServiceTest (5 tests) ✨ NEW HITL Tests
- FraudContextServiceTest (8 tests)

### Test Categories

| Category | Tests | Focus |
|----------|-------|-------|
| Input Validation | 8 | null/blank handling, format validation |
| Business Logic | 10 | ACID transaction simulation, escalation logic |
| Integration | 6 | Service collaboration, mocking |
| HITL | 5 | Escalation freezing, audit logging, edge cases |

### HITL-Specific Test Coverage (HitlEscalationServiceTest)

**Test Cases:**
1. `freezeStateAndEscalate_withNullSessionId_throwsException` - Validates null session ID rejected
2. `freezeStateAndEscalate_withBlankSessionId_throwsException` - Validates blank session ID rejected
3. `freezeStateAndEscalate_withValidInputs_callsAuditLogging` - Verifies audit trail created
4. `freezeStateAndEscalate_withNullReasoning_worksCorrectly` - Gracefully handles null reasoning
5. `freezeStateAndEscalate_withEmptyReasoning_worksCorrectly` - Gracefully handles empty reasoning

### Queryable Encryption Tests (New)

**Test Cases:**
1. `queryableEncryption_keyGenerationOnInit` - Verify DEK created in key vault
2. `queryableEncryption_queryOnEncryptedEmail` - Deterministic encryption allows email queries
3. `queryableEncryption_decryptionAuthorizedAudit` - Audit access with permission logging
4. `queryableEncryption_keyRotation` - Verify hourly key rotation mechanism
5. `queryableEncryption_schemaCompliance` - Validate all PII fields encrypted per schema

**Mock Strategy:**
```java
@Mock private AuditLoggingService auditLoggingService;
@Mock private MongoTemplate hitlTemplate;

// Service properly injected with mocked dependencies
@InjectMocks private HitlEscalationService hitlEscalationService;
```

### Testing Queryable Encryption Initialization

**Start Backend with Debug Logging:**
```bash
export ENCRYPTION_LOG_LEVEL=DEBUG
mvn spring-boot:run
# Look for: ✅ Queryable Encryption Key Generated - KeyId: <uuid>
```

**Verify Key Vault Creation:**
```bash
# Connect to MongoDB
docker-compose exec mongodb-atlas mongosh

# Check key vault collection
use keyvault
db.keys.find({keyType: "local"})

# Expected output:
# { _id: ObjectId(...), keyType: "local", key: BinData(...), createdAt: ISODate(...), status: "ACTIVE" }
```

**Query on Encrypted Fields:**
```bash
curl -X POST http://localhost:8080/api/v1/users/find-by-email \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com"}'
# MongoDB auto-encrypts plaintext and queries against encrypted value
```

### Testing Local HITL API

**Start Backend:**
```bash
mvn spring-boot:run
# Server runs on http://localhost:8080
```

**Test User Appeal Endpoint:**
```bash
curl -X POST http://localhost:8080/api/v1/agent/appeal \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-session-001",
    "appealReason": "I believe this transaction is legitimate"
  }'
```

**Query Pending Escalations:**
```bash
curl http://localhost:8080/api/v1/operator/escalations/pending
```

**Approve an Escalation:**
```bash
curl -X POST http://localhost:8080/api/v1/operator/escalations/{escalation_id}/approve \
  -H "Content-Type: application/json" \
  -d '{
    "operatorId": "operator-test-1",
    "operatorNotes": "Verified - transaction approved"
  }'
```

### Code Quality

- Zero compilation errors (55+ fixed during migration)
- All Lombok annotations migrated to manual fields for Java 21 compatibility
- Jackson for type-safe JSON handling
- SLF4J logging throughout
- HITL component tests: 5/5 passing ✅

---

## 11. Troubleshooting Guide

### Build Fails with Lombok Errors

**Symptom:** `"cannot find symbol: variable log"`

**Solution:**
- Use manual SLF4J fields instead of `@Slf4j`
- Remove Lombok annotation processing from compiler config

### Queryable Encryption Key Not Generated

**Symptom:** Application starts but no "✅ Queryable Encryption Key Generated" log message

**Debugging:**
```bash
# Check if key already exists
docker-compose exec mongodb-atlas mongosh
use keyvault
db.keys.countDocuments({keyType: "local"})

# If > 0: Key exists, app uses existing key (idempotent)
# If 0: Check application logs for error
docker-compose logs api-gateway | grep -i "encryption\|QE_KEY"
```

**Solution:**
- Ensure `keyvault` database exists and is writable
- Verify MongoDB auth credentials in `application.properties`
- Check that `DatabaseInitializer` bean is being loaded: `@Component` annotation present

### MongoClient Connection Pool Exhaustion

**Symptom:** `"Cannot get a connection, pool error"`

**Solution:**
- Check MongoDB health: `docker-compose logs mongodb-atlas`
- Verify connection limits in application.properties
- Ensure @PreDestroy hooks are closing connections

### HITL Escalations Not Appearing in Dashboard

**Symptom:** Empty escalations list even after appeals submitted

**Debugging Steps:**
```bash
# Check if escalations are in MongoDB
docker-compose exec mongodb-atlas mongosh
> use pass_hitl
> db.hitl_escalations.find()

# Verify API is returning data
curl http://localhost:8080/api/v1/operator/escalations/pending | jq

# Check browser console for frontend errors
# Check server logs: docker-compose logs api-gateway | grep -i hitl
```

### Operator Dashboard Not Loading

**Symptom:** 404 error on `/operator/dashboard`

**Check:**
1. Frontend route configured in Next.js layout
2. HitlOperatorDashboard component properly imported
3. API endpoints accessible from frontend (check CORS settings)

### Embedding Service Timeout

**Symptom:** `"Connection timeout to embedding-service:8000"`

**Solution:**
- Verify container is running: `docker-compose ps`
- Check health: `curl http://localhost:8001/health`
- View logs: `docker-compose logs embedding-service`

---

## 12. Architecture Principles

| Principle | Application |
|-----------|:----------:|
| **Single Responsibility** | Each agent has one core responsibility |
| **Fail-Safe Defaults** | Zero-trust starts, gracefully degrades to HITL |
| **Immutable Audit Trail** | All decisions logged with timestamps and reasoning |
| **Semantic Consistency** | Vector search ensures behavioral validation |
| **Deterministic Execution** | LLM reasoning → deterministic Java tools |
| **Resource Bounds** | Connection pools, timeouts, and memory limits enforced |
| **Privacy by Default** | All PII encrypted with Queryable Encryption at rest |
| **Zero-Knowledge Server** | Server processes ciphertext; decryption client-side only |
| **Queryable Encryption** | Deterministic algorithm enables secure queries on encrypted PII |
| **Key Rotation** | Hourly key rotation for compliance; audit trail maintained |

---

## 13. Future Enhancements

- [ ] Multi-language LLM support (expand beyond qwen)
- [ ] Advanced anomaly detection with statistical baselines
- [ ] Custom fraud rule engine alongside AI reasoning
- [ ] Kubernetes orchestration for auto-scaling
- [ ] Performance monitoring with Prometheus metrics
- [ ] Batch processing for high-volume mandate switches
