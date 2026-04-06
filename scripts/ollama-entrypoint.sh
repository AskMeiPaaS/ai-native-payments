#!/bin/bash
# =============================================================================
# Ollama LLM Entrypoint Script
# Serves: LLM with automatic model download on startup
# Default Model: LESSTHANSUPER/TRINITY_MINI-26b:IQ3_XS
# Companion Services: Voyage AI APIs (embeddings + reranking)
# =============================================================================
set -euo pipefail

echo "═════════════════════════════════════════════════════════════"
echo "🚀 Ollama LLM Service - Entrypoint"
echo "═════════════════════════════════════════════════════════════"

# ── Configuration ────────────────────────────────────────────────────────────
export LLM_MODEL_NAME="${LLM_MODEL_NAME:-deepseek-r1}"
export OLLAMA_HOST="${OLLAMA_HOST:-0.0.0.0:11434}"
export OLLAMA_PULL_TIMEOUT=3600  # 1 hour (3600 seconds) for model download on slow networks

echo "📋 Configuration:"
echo "   Model: $LLM_MODEL_NAME"
echo "   Host:  $OLLAMA_HOST"
echo "   Timeout: $OLLAMA_PULL_TIMEOUT seconds"
echo ""

# ── 1. Start Ollama server ───────────────────────────────────────────────────
echo "🔧 Starting Ollama server..."
ollama serve &
OLLAMA_PID=$!
echo "   PID: $OLLAMA_PID"
echo ""

# ── 2. Wait for API readiness ────────────────────────────────────────────────
echo "⏳ Waiting for Ollama API endpoint to be ready..."
MAX_ATTEMPTS=120  # Extended to 2 minutes (2s * 120)
ATTEMPT=0

until curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; do
    ATTEMPT=$((ATTEMPT + 1))
    if [ "$ATTEMPT" -ge "$MAX_ATTEMPTS" ]; then
        echo "❌ FAILED: Ollama API did not respond after $((MAX_ATTEMPTS * 2)) seconds"
        kill $OLLAMA_PID 2>/dev/null || true
        exit 1
    fi
    echo "   Attempt $ATTEMPT/$MAX_ATTEMPTS (waiting $((ATTEMPT * 2))s)..."
    sleep 2
done
echo "✅ Ollama API is ready!"
echo ""

# ── 3. Check/Download LLM model ──────────────────────────────────────────────
echo "🤖 LLM Model Setup"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Extract model base name (before the colon if present)
MODEL_BASE=$(echo "$LLM_MODEL_NAME" | sed 's/:.*$//')
echo "   Checking for: $MODEL_BASE"

# Check if the exact model tag already exists
if ollama list 2>/dev/null | awk '{print $1}' | grep -qi "^${LLM_MODEL_NAME}$"; then
    echo "✅ Model already cached: $LLM_MODEL_NAME"
    echo ""
else
    echo "📥 Model not found. Attempting download..."
    echo "   Target: $LLM_MODEL_NAME"
    echo "   Timeout: $((OLLAMA_PULL_TIMEOUT / 60)) minutes"
    echo ""
    
    # Try to pull the specified model
    if timeout $OLLAMA_PULL_TIMEOUT ollama pull "$LLM_MODEL_NAME" 2>&1; then
        echo ""
        echo "✅ Model downloaded successfully!"
    else
        PULL_EXIT=$?
        echo ""
        echo "⚠️  Failed to pull '$LLM_MODEL_NAME' (exit: $PULL_EXIT)"
        echo ""
        
        # Try fallback models
        FALLBACK_MODELS=("neural-chat" "llama2" "mistral" "orca-mini")
        MODEL_FOUND=0
        
        for fallback_model in "${FALLBACK_MODELS[@]}"; do
            echo "🔄 Attempting fallback: $fallback_model"
            if timeout 1800 ollama pull "$fallback_model" 2>&1; then
                echo "✅ Fallback model '$fallback_model' downloaded successfully"
                MODEL_FOUND=1
                break
            else
                echo "   ⚠️  Failed to pull $fallback_model"
            fi
        done
        
        if [ $MODEL_FOUND -eq 0 ]; then
            echo "❌ Could not download any model (primary or fallback)"
            echo "   Please check network connectivity and try again"
            kill $OLLAMA_PID 2>/dev/null || true
            exit 1
        fi
    fi
fi

# ── 4. Display registered models ─────────────────────────────────────────────
echo ""
echo "📋 Available Models in Ollama:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ollama list 2>/dev/null || echo "   (Could not enumerate models)"
echo ""

# ── 5. Warm up model (pre-load into memory) ──────────────────────────────────
echo ""
echo "🔥 Warming up model: $LLM_MODEL_NAME (pre-loading into memory)..."
echo "   This avoids cold-start timeouts on the first API request."
WARMUP_RESPONSE=$(curl -sf --max-time 300 http://localhost:11434/api/generate \
    -d "{\"model\": \"$LLM_MODEL_NAME\", \"prompt\": \"hi\", \"stream\": false}" 2>&1) || true
if echo "$WARMUP_RESPONSE" | grep -q '"response"'; then
    echo "✅ Model warmed up and loaded into memory!"
else
    echo "⚠️  Warmup did not get a full response (model may still be loading)."
    echo "   First user request may be slower."
fi

# ── 6. System Status ─────────────────────────────────────────────────────────
echo ""
echo "✅ READY: Ollama LLM service is running"
echo "═════════════════════════════════════════════════════════════"
echo "   🎯 LLM Endpoint:     http://localhost:11434"
echo "   🤖 Active Model:     $LLM_MODEL_NAME"
echo "   🌐 Companion APIs:   Voyage AI (embeddings + reranking)"
echo "═════════════════════════════════════════════════════════════"
echo ""

# ── 7. Keep server running with auto-restart ────────────────────────────────
echo "🔄 Ollama server is running. Monitoring for crashes..."
echo ""

# Monitor and restart if server crashes
while true; do
    if ! kill -0 "$OLLAMA_PID" 2>/dev/null; then
        echo "⚠️  Ollama server crashed! Restarting..."
        sleep 2
        ollama serve &
        OLLAMA_PID=$!
        echo "✅ Ollama restarted with PID: $OLLAMA_PID"
        
        # Wait for API to be ready again
        for i in {1..30}; do
            if curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; then
                echo "✅ API ready after restart"
                break
            fi
            sleep 1
        done
    fi
    sleep 5
done

