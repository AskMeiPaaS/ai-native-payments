# 🚀 ai-native-payments: Autonomous PaSS Orchestration

**ai-native-payments** is a next-generation financial orchestration engine built for the **RBI Payments Switching Service (PaSS)**.

---

## ⚠️ SECURITY DISCLAIMER

> **🛑 REFERENCE IMPLEMENTATION ONLY**
>
> This is a **demonstration and educational project** for testing purposes. 
>
> **❌ NOT FOR PRODUCTION USE OR REAL FINANCIAL TRANSACTIONS**
>
> ### Critical Security Features MISSING:
> - ❌ **No Authentication/Authorization** — users are not verified
> - ❌ **No Rate Limiting** — vulnerable to DoS attacks
> - ❌ **No HTTPS/TLS** — communications not encrypted
> - ❌ **No CSRF Protection** — susceptible to cross-site attacks
> - ❌ **No Real Payment Integration** — uses mock data only
> - ❌ **Uses Demo Credentials** — hardcoded defaults for development
> - ❌ **Limited Input Validation** — potential injection risks
>
> ### Before ANY Production Use:
> 1. ✅ Implement Spring Security with JWT authentication
> 2. ✅ Add rate limiting and API key management
> 3. ✅ Enable HTTPS/TLS for all communications
> 4. ✅ Integrate real payment processor (authorized by RBI)
> 5. ✅ Conduct security audit and penetration testing
> 6. ✅ Obtain compliance certification (ISO 27001, PCI-DSS if needed)
> 7. ✅ Perform code review with security experts
>
> **See [SECURITY_ASSESSMENT.md](SECURITY_ASSESSMENT.md) for detailed findings.**

---

Unlike traditional applications where AI is just a feature, this platform is strictly **AI-Native** and **Responsible by Design**. The application logic is driven dynamically by a **Multi-Agent System (MAS)** that reasons over user intent, continuously authenticates via behavioral telemetry, and autonomously executes banking operations.

---

## 🧠 What Makes This Different

- **Agentic Orchestration:** A Supervisor LLM determines the workflow dynamically based on user intent and real-time context
- **Semantic Memory:** MongoDB Atlas acts as the agent's brain (Time-Series for Short-Term memory, Vector Search for baselines)
- **Tool-Driven Execution:** Java 21 exposes atomic, ACID-compliant banking functions as `@Tool` annotations
- **Zero-Trust Authentication:** Continuous implicit authentication via streaming device and biometric telemetry
- **Explainable AI:** Every decision includes transparent reasoning for users and regulators

---

## 🛡️ Responsible AI by Design

We implement Google's PAIR guidelines at the architectural level:

| Principle | Implementation |
|-----------|-----------------|
| **Privacy** | PII Sanitization Service - LLMs operate on tokenized, pseudonymized data |
| **Fairness** | Behavioral embeddings calibrated to prevent accessibility bias |
| **Human Agency** | HITL protocol ensures users can appeal or override AI decisions anytime |
| **Explainability** | Complex vector scores translated to human-readable transparency cards |

---

## 🛠️ Tech Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| **Frontend** | Next.js + React + TypeScript | Streaming chat UI, operator dashboard, HITL appeals |
| **Backend** | Java 21 + Spring Boot + LangChain4j | Multi-agent orchestration with virtual threads |
| **Memory** | MongoDB Local Atlas | Unified semantic memory, audit store, vector search |
| **Embeddings** | Voyage-4 (API) | High-quality embeddings via Voyage AI (1024-dim) |
| **Reranking** | Voyage Reranker (API) | Lightweight relevance scoring (rerank-lite-1, top-2) |
| **LLM** | Ollama + Qwen 2.5 3B | Local reasoning with real-time token streaming |

---

## 📦 Modular Package Layout

The backend is now organized into clearer responsibility-based modules:

| Package | Responsibility |
|---------|----------------|
| `com.ayedata.ai` | Orchestration agents, tools, context enrichment, streaming supervisor |
| `com.ayedata.audit` | Session-aware audit logging, API request/response capture, compliance hooks |
| `com.ayedata.config` | MongoDB, model, HTTP client, and runtime infrastructure config |
| `com.ayedata.controller` | Shared REST/SSE entry points used across features |
| `com.ayedata.domain` | Core payment and profile entities |
| `com.ayedata.hitl` | Appeals, operator review APIs, escalation records, HITL initialization |
| `com.ayedata.init` | Startup orchestration for cross-cutting infrastructure |
| `com.ayedata.rag` | Knowledge ingestion, retrieval, reranking, and seeders |
| `com.ayedata.service` | Shared services such as memory and ledger coordination |

This keeps feature concerns easier to extend independently while preserving Spring Boot auto-scanning under the root `com.ayedata` package.

---

## 🚀 Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 21+
- Maven 3.9+
- Node.js 18+

> **API Key Required:** You'll need a Voyage AI API key for embeddings and reranking. Get one at [voyageai.com](https://voyageai.com).

### 1. Start Infrastructure
```bash
docker-compose up -d
```

This brings up:
- MongoDB Local Atlas (port 27017) with automatic credentials setup
- Ollama with configurable LLM (port 11434)
- Java backend (api-gateway) (port 8080)
- Next.js frontend (agent-ui) (port 3000)

### 2. Build & Run Backend
```bash
mvn clean package -DskipTests
java -jar target/ai-native-payments-1.0.0.jar
```

Backend runs on `http://localhost:8080`

### 3. Run Frontend
```bash
cd agent-ui
npm install
npm run dev
```

Frontend runs on `http://localhost:3000`

---

## 🚀 Deployment Guide

### 1️⃣ Prerequisites

Before starting, ensure you have:
- Docker & Docker Compose installed
- Java 21+
- Maven 3.9+
- Node.js 18+
- **Voyage AI API key** (free tier available at [voyageai.com](https://voyageai.com))

### 2️⃣ Configure Environment

Edit `.env` and add your Voyage API key:

```bash
# In .env, replace placeholder with your actual key
VOYAGE_API_KEY=<your-voyage-ai-api-key-here>

# Optional: Choose LLM model
LLM_MODEL_NAME=qwen2.5:3b              # Current default: faster local model with streaming
```

### 3️⃣ Start Infrastructure

```bash
# Pull latest images and start services
docker-compose pull
docker-compose up -d

# Monitor Ollama model download (first run ~10-15 minutes)
docker logs -f ollama
```

### 4️⃣ Build & Deploy Backend

```bash
# Compile and build fat JAR
mvn clean package -DskipTests

# Verify all services are running
docker-compose ps

# Check API health
curl http://localhost:8080/actuator/health
```

### 5️⃣ Run Frontend

```bash
cd agent-ui
npm install
npm run dev
```

Access the application at `http://localhost:3000`

### 🧪 Verification

```bash
# Check all services running
docker-compose ps

# Verify API is healthy
curl -s http://localhost:8080/actuator/health | jq '.status'

# Check Ollama model is loaded
curl -s http://localhost:11434/api/tags | jq '.models[].name'

# Verify MongoDB is accessible
mongosh "mongodb://admin:mongoadmin123@localhost:27017/pass_main?authSource=admin"
```

### 🛠️ Troubleshooting

| Issue | Solution |
|-------|----------|
| Docker not running | Start Docker Desktop |
| Out of memory | Increase Docker memory in settings (6-8GB recommended) |
| Ollama stuck downloading | Give it time (10-15 min) or check internet |
| API returning 500 errors | Check logs with `docker logs api-gateway` |
| Slow inference | Use `qwen2.5:3b` for faster local responses |
| Services won't start | Check `docker-compose logs` for detailed errors |

---

## 📊 System Architecture

```
┌─────────────────────────────┐
│    Next.js Generative UI    │
│      (Port 3000)            │
└────────────┬────────────────┘
             │
             ▼
┌─────────────────────────────────────┐
│   Java 21 Multi-Agent Orchestrator  │
│     (LangChain4j | Port 8080)       │
│                                     │
│  • Supervisor Agent                 │
│  • Context/Fraud Agent              │
│  • Ledger Agent (@Tool Caller)      │
└──────┬──────────┬──────────┬────────┘
       │          │          │
       ▼          ▼          ▼
  ┌────────────────┐  ┌──────────────────────┐  ┌──────────────┐
  │  MongoDB       │  │ Voyage AI APIs       │  │ Ollama LLM   │
  │  Local Atlas   │  │ • Embeddings (v4)    │  │ (Port 11434) │
  │  (Port 27017)  │  │ • Reranking (lite-1) │  └──────────────┘
  └────────────────┘  └──────────────────────┘
```

---

## 🔄 The Autonomous Flow

1. **Intent & Telemetry:** User asks to switch a payment mandate. UI captures intent and biometric telemetry.
2. **PII Sanitization:** Backend sanitizes all personally identifiable information before passing to LLM.
3. **Context Verification:** Context Agent queries vector search to ensure current telemetry matches historical baseline.
4. **Reasoning:** Supervisor Agent reasons about the request, confirms identity, and plans execution.
5. **Tool Execution:** Supervisor autonomously calls Java `@Tool` method for atomic transaction.
6. **Ledger Commit:** ACID transaction safely commits in MongoDB.
7. **Transparent Response:** Agent streams natural language confirmation with security reasoning.

---

## 💳 Payment Method Routing via RAG

The system uses **Retrieval-Augmented Generation (RAG)** to surface payment-routing guidance to the LLM. The LLM chooses the payment channel during orchestration, and the backend persists that choice without recalculating it in Java.

### How It Works

When a user initiates a transfer, the agent reads the retrieved payment knowledge and chooses the channel to pass into the ledger tool:

1. **RAG Knowledge Base** — Retrieves RBI-compliant routing rules and channel guidance
2. **Current Request** — Amount, transfer intent, and beneficiary context
3. **Conversation Context** — Prior chat history and any risk-related hints surfaced to the agent

### What Is Not Hardcoded

- There is no Java `if/else` ladder that maps amount bands to UPI, NEFT, or RTGS.
- There is no backend risk-profile switch that rewrites the chosen channel.
- The backend stores the channel chosen by the LLM and records it in the ledger and audit trail.

### RAG Knowledge Used By The Agent

The agent can retrieve channel and compliance guidance from documents such as:

| Document | Contains | Used For |
|----------|----------|----------|
| `payment-routing-risk-matrix.txt` | RBI guidance, risk notes, routing guardrails | Channel-selection context |
| `upi-payment-channel.txt` | UPI limits, speeds, regulations | UPI-related retrievals |
| `neft-payment-channel.txt` | NEFT availability, windows, daily caps | NEFT-related retrievals |
| `rtgs-payment-channel.txt` | RTGS requirements and settlement properties | RTGS-related retrievals |

### Example Payment Decision Flow

```
User initiates ₹50,000 transfer
    ↓
RAG retrieval enriches the prompt with payment guidance
    ↓
LLM reads the retrieved context and chooses NEFT for this request
    ↓
Tool call: transferFunds("beneficiary", "NEFT", 50000)
    ↓
Transaction stored with:
  • paymentMethod: "NEFT"
  • requiresHitlReview: false
  • audit trail tied to the orchestrated tool call
```

### API Usage

**Account Dashboard (shows recent transfers with payment methods):**
```bash
curl -X GET "http://localhost:8080/api/v1/account/dashboard?userId=demo-user" \
  -H "Content-Type: application/json"
```

**Response includes:**
```json
{
  "userId": "demo-user",
  "currentBalance": 4300.00,
  "recentTransfers": [
    {
      "amount": 50000,
      "transactionType": "DEBIT",
      "sourceAccount": "demo-user",
      "targetAccount": "merchant-1",
      "targetBank": "NEFT",
      "status": "SETTLED"
    }
  ]
}
```

### Receiving Funds via Chat (AI-Identified Channel)

Users can request inbound credits directly through the chat. The agent calls the `receiveFunds` tool and chooses the receive channel from retrieved payment guidance. The examples below reflect the current RAG corpus, not a hardcoded backend rule table.

#### Example Channel Outcomes From Current Knowledge

| Amount | Recommended Channel | Reason |
|--------|-------------------|--------|
| `< ₹1,000` | **UPI** | Instant settlement, zero fees, up to ₹5L/day |
| `₹1,000 – ₹1,99,999` | **NEFT** | Batch settlement, nominal fee, any amount |
| `≥ ₹2,00,000` | **RTGS** | Real-time gross settlement, same day, min ₹2L |

#### Example Chat Interactions

```
User: "add ₹500 to my account"
  → Agent calls receiveFunds(500)
  → Channel identified from RAG: UPI
  → Reply: "₹500.00 received via UPI. Your new account balance is ₹4,800.00."

User: "I received ₹50,000 from a client"
  → Agent calls receiveFunds(50000)
  → Channel identified from RAG: NEFT
  → Reply: "₹50,000.00 received via NEFT. Your new account balance is ₹54,800.00."

User: "deposit ₹3,00,000"
  → Agent calls receiveFunds(300000)
  → Channel identified from RAG: RTGS
  → Reply: "₹3,00,000.00 received via RTGS. Your new account balance is ₹3,54,800.00."
```

**REST API equivalent:**
```bash
curl -X POST "http://localhost:8080/api/v1/account/topup" \
  -H "Content-Type: application/json" \
  -d '{"userId": "demo-user", "amount": 50000, "method": "NEFT"}'
```

---

## 🧑‍💻 Human-in-the-Loop (HITL)

The system never traps users in a "black box". Full HITL interface implemented for both users and operators:

### HITL Interaction Flow

```mermaid
graph TD
    A["👤 User Chat Interface<br/>Agent Dashboard"] -->|"1. AI Decision Made"| B["Decision Displayed in Chat"]
    B -->|"2. User Clicks<br/>Appeal Button"| C["HitlAppealButton<br/>Component Shows Modal"]
    C -->|"3. Enter Appeal Reason"| D["User Types:<br/>Why they disagree<br/>with AI decision"]
    D -->|"4. Submit Appeal<br/>POST /api/v1/agent/appeal"| E["Backend:<br/>freezeStateAndEscalate"]
    E -->|"✅ Creates escalation record<br/>Generates escalationId<br/>Status: PENDING_HUMAN_REVIEW"| F["MongoDB<br/>pass_hitl DB<br/>escalations collection"]
    F -->|"Returns escalationId<br/>to UI"| G["User Sees:<br/>✓ Appeal Recorded<br/>Escalation ID displayed"]
    
    H["👨‍💼 Operator Dashboard<br/>HitlOperatorDashboard"] -->|"5. Dashboard Polls<br/>every 10s<br/>GET /api/v1/operator/escalations/pending"| I["Fetch Pending Cases"]
    I -->|"Returns list of<br/>PENDING escalations"| J["Dashboard Shows:<br/>📋 All pending cases<br/>with AI reasoning"]
    J -->|"6. Operator Clicks<br/>on a case"| K["Show Escalation Details:<br/>• Session ID<br/>• Appeal reason<br/>• AI reasoning<br/>• Transaction context"]
    K -->|"7. Operator Reviews &<br/>Takes Action"| L{Operator Decision}
    L -->|"Approve"|M["POST /api/v1/operator/escalations/{id}/approve"]
    L -->|"Deny"|N["POST /api/v1/operator/escalations/{id}/deny"]
    L -->|"Override"|O["POST /api/v1/operator/escalations/{id}/override"]
    L -->|"Cancel"|P["POST /api/v1/operator/escalations/{id}/cancel"]
    
    M --> Q["✅ Transaction Executes<br/>Status: APPROVED"]
    N --> Q2["❌ Transaction Rolled Back<br/>Status: DENIED"]
    O --> Q3["🔧 Manual Override Executed<br/>Status: MANUAL_OVERRIDE"]
    P --> Q4["⛔ Transaction Cancelled<br/>Status: CANCELLED"]
    
    Q --> R["Update MongoDB<br/>+ Audit Log"]
    Q2 --> R
    Q3 --> R
    Q4 --> R
    R --> S["User Notified<br/>of Resolution"]
```

### User-Facing HITL

**Appeal Button in Chat UI:**
```tsx
<HitlAppealButton sessionId={sessionId} />
```

Users can click "Appeal / Speak to Human" to:
1. Describe why they disagree with the AI decision
2. Optionally include context about their situation
3. Submit for human operator review

**Endpoint:** `POST /api/v1/agent/appeal`
```json
{
  "sessionId": "user-session-123",
  "appealReason": "I believe this is a legitimate transaction"
}
```

### Operator-Facing HITL Dashboard

**Access Operator Dashboard:**
1. Navigate to `/operator/dashboard` (requires operator authentication)
2. View all pending escalations in real-time
3. Approve, deny, override, or cancel decisions

**Key Operator Endpoints:**

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/operator/escalations/pending` | GET | List all pending HITL cases |
| `/api/v1/operator/escalations/{id}` | GET | Get escalation details |
| `/api/v1/operator/escalations/{id}/approve` | POST | Approve AI decision → execute transaction |
| `/api/v1/operator/escalations/{id}/deny` | POST | Reject transaction → rollback |
| `/api/v1/operator/escalations/{id}/override` | POST | Execute manual decision instead of AI |
| `/api/v1/operator/escalations/{id}/cancel` | POST | Cancel transaction entirely |

**Example Operator Action:**
```bash
curl -X POST http://localhost:8080/api/v1/operator/escalations/{id}/approve \
  -H "Content-Type: application/json" \
  -d '{
    "operatorId": "op-john-doe",
    "operatorNotes": "Verified with customer. Transaction is legitimate."
  }'
```

### Operator Case Resolution Workflow

```mermaid
graph TD
    A["Operator Accesses<br/>/operator/dashboard"] -->|"Page Loads"| B["Fetch Pending Cases<br/>GET /api/v1/operator/escalations/pending"]
    B -->|"Every 10 seconds"| C["Display Case List"]
    C -->|"Stats Shown"| D["📊 Pending | Resolved | Total"]
    D -->|"Operator Clicks Case"| E["Case Details Expand<br/>Show full context"]
    E -->|"Operator Reviews"| F["📖 Read:<br/>• Appeal reason<br/>• AI reasoning<br/>• Account history<br/>• Transaction details"]
    F -->|"Operator Decides"| G{Choose Action}
    
    G -->|"✅ APPROVE"| H["POST .../approve"]
    G -->|"❌ DENY"| I["POST .../deny"]
    G -->|"🔧 OVERRIDE"| J["POST .../override"]
    G -->|"⛔ CANCEL"| K["POST .../cancel"]
    
    H -->|"Backend: resolveEscalation"| L["Generate TxnID<br/>Status: APPROVED<br/>Log operator info"]
    I -->|"Backend: resolveEscalation"| M["No TxnID<br/>Status: DENIED<br/>Rollback transaction"]
    J -->|"Backend: resolveEscalation"| N["Generate TxnID<br/>Status: OVERRIDE<br/>Execute custom action"]
    K -->|"Backend: resolveEscalation"| O["No TxnID<br/>Status: CANCELLED<br/>Abandon case"]
    
    L --> P["Save to MongoDB<br/>pass_hitl"]
    M --> P
    N --> P
    O --> P
    
    P -->|"Audit Log"| Q["📝 Immutable Record:<br/>operatorId, action,<br/>timestamp, notes"]
    Q -->|"Success Response"| R["✅ Case resolved<br/>Dashboard refreshes<br/>Case removed from pending"]
    R -->|"User Notification"| S["Notify User:<br/>Appeal resolved<br/>Include decision + notes"]
```

**Operator Resolution Actions:**

| Action | Endpoint | Effect | Transaction ID | Status | Use Case |
|--------|----------|--------|---|--------|----------|
| **APPROVE** ✅ | `POST .../approve` | Execute AI decision | ✅ Generated | `RESOLVED_APPROVED` | Operator confirms AI decision is correct |
| **DENY** ❌ | `POST .../deny` | Reject & rollback | ❌ None | `RESOLVED_DENIED` | Operator determines transaction is fraudulent |
| **OVERRIDE** 🔧 | `POST .../override` | Execute custom action | ✅ Generated | `RESOLVED_MANUAL_OVERRIDE` | Operator executes different action than AI suggested |
| **CANCEL** ⛔ | `POST .../cancel` | Abandon transaction | ❌ None | `RESOLVED_CANCELLED` | Operator decides not to proceed |

**Example Requests:**

Approve:
```json
{
  "operatorId": "op-john-doe",
  "operatorNotes": "Verified customer identity. Transaction is legitimate."
}
```

Override:
```json
{
  "operatorId": "op-jane-smith",
  "operatorNotes": "Customer confirmed via phone. Approving modified amount: $500 instead of $1000.",
  "actionIntent": "PARTIAL_APPROVAL"
}
```

**Response (all actions):**
```json
{
  "escalationId": "ESC_2026040412345",
  "status": "RESOLVED_APPROVED",
  "transactionId": "TXN_PASS_55443322",
  "operatorId": "op-john-doe",
  "operatorNotes": "Verified customer identity. Transaction is legitimate.",
  "resolvedAt": "2026-04-04T12:40:00Z"
}
```

### Escalation Flow

```
1. User Request
   ├─ System-Initiated: Anomaly detected → Auto-escalate
   └─ User-Initiated: "Appeal / Speak to Human" clicked

2. State Freeze
   └─ Transaction paused in MongoDB
   └─ Context + AI reasoning logged

3. Operator Review
   ├─ Dashboard shows pending case
   ├─ Operator reviews AI reasoning + telemetry
   └─ Operator makes decision (approve/deny/override)

4. Resolution
   ├─ If APPROVED: Transaction executes
   ├─ If DENIED: Transaction rolls back
   ├─ If MANUAL_OVERRIDE: Custom action executes
   └─ If CANCELLED: Transaction abandoned

5. Audit Trail
   └─ All decisions immutably logged with operator ID + timestamp
```

### Data Model

**HitlEscalationRecord (MongoDB - `hitl_escalations`):**
```json
{
  "_id": "escalation-123",
  "sessionId": "user-session-123",
  "reasoning": "Context confidence: 0.65 < threshold: 0.80",
  "status": "PENDING_HUMAN_REVIEW",
  "appealSource": "USER_INITIATED",
  "createdAt": "2026-04-04T10:30:00Z",
  "operatorId": "op-john-doe",
  "operatorNotes": "Customer verified. Approved.",
  "resolvedAt": "2026-04-04T10:35:00Z"
}
```

**Status Values:**
- `PENDING_HUMAN_REVIEW` - Awaiting operator decision
- `RESOLVED_APPROVED` - Operator approved, transaction executed
- `RESOLVED_DENIED` - Operator rejected, transaction rolled back
- `RESOLVED_MANUAL_OVERRIDE` - Operator executed custom action
- `RESOLVED_CANCELLED` - Transaction cancelled

---

## 📁 Project Structure

```
ai-native-payments/
├── src/main/java/com/ayedata/
│   ├── controller/
│   │   ├── PaSSController.java         # User orchestration endpoints
│   │   └── HitlOperatorController.java # Operator dashboard endpoints (new!)
│   ├── ai/
│   │   ├── agent/          # Multi-agent orchestrators
│   │   ├── scoring/        # Custom scoring models
│   │   └── tools/          # LLM-callable tools (@Tool)
│   ├── service/
│   │   ├── HitlEscalationService.java  # HITL escalation logic (enhanced!)
│   │   └── ...             # Other services
│   ├── domain/
│   │   ├── HitlEscalationRecord.java   # HITL data model (extended!)
│   │   └── ...             # Other domain models
│   ├── config/             # Spring configuration
│   └── init/               # Database initialization
├── agent-ui/               # Next.js frontend
│   ├── src/components/
│   │   ├── HitlAppealButton.tsx        # User appeal component (new!)
│   │   ├── HitlOperatorDashboard.tsx   # Operator dashboard (new!)
│   │   └── ...             # Other components
│   └── ...
├── docker-compose.yaml     # Container orchestration
├── ARCHITECTURE.md         # Detailed technical architecture
└── pom.xml                 # Maven dependencies
```

---

## 🔐 Security & Compliance

- **Zero-Trust:** Every session verified against behavioral baseline
- **ACID Transactions:** All state changes are atomic and immutable
- **Audit Trail:** Every AI decision logged in immutable `ai_reasoning_logs`
- **Data Masking:** PII never exposed to LLMs
- **RBI Compliance:** Full support for PaSS mandate framework
- **HITL Access Control:** Operator endpoints require authentication (implement role-based access in production)

---

## 🆘 Troubleshooting

### Containers fail to start
```bash
# Check service health
curl http://localhost:11434/api/tags       # Ollama
curl http://localhost:8080/actuator/health # API Gateway

# Verify Voyage AI connectivity (requires API key in .env)
grepecho "VOYAGE_API_KEY from .env"
```

### MongoDB connection fails
```bash
# Test connectivity
docker-compose exec api-gateway curl mongodb://mongodb-atlas:27017
```

### View logs
```bash
docker-compose logs -f api-gateway
docker-compose logs -f ollama
```

---

## 📚 Documentation & Resources

### Core Documentation
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — Complete system architecture, data flows, and design patterns

### Getting Started
- ✅ **[README.md](README.md)** — You are here! Quick start and deployment guide

### Configuration Files
- **[docker-compose.yaml](docker-compose.yaml)** — Multi-platform Docker Compose with multi-architecture support
- **[.env](.env)** — Environment configuration for all dependencies

### Additional Guides
- **[SKILLS.md](SKILLS.md)** — Custom capabilities and extensions
- **[PROMPT.md](PROMPT.md)** — System prompts for AI agents

---

## 🤝 Contributing

Extend this platform by:
1. Adding new `@Tool` methods for additional banking operations
2. Integrating additional embedding models or rerankers
3. Extending the HITL protocol with custom approval workflows
4. Implementing additional fraud detection patterns

---

## 📄 License

Refer to LICENSE file for details.
