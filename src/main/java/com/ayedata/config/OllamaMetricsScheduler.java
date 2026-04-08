package com.ayedata.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Passive Ollama performance tracker.
 *
 * Instead of sending a synthetic probe (which blocks Ollama while it is busy
 * serving real requests and causes timeouts), this component receives metrics
 * published by {@link com.ayedata.controller.PaSSController} at the end of
 * every real streaming turn via {@link #record(int, int, long)}.
 *
 * The {@code @Scheduled} method runs every 30 seconds and simply logs the last
 * captured snapshot — no outbound HTTP call, no timeout risk.
 *
 * Metrics captured:
 *   outputTokens — tokens generated in the final reply (LangChain4j TokenUsage.outputTokenCount)
 *   inputTokens  — prompt tokens consumed (TokenUsage.inputTokenCount)
 *   elapsedMs    — end-to-end wall-clock time for the full orchestration turn
 *   tps          — outputTokens / elapsedSeconds (end-user-perceived generation rate)
 */
@Component
public class OllamaMetricsScheduler {

    private static final Logger log = LoggerFactory.getLogger(OllamaMetricsScheduler.class);

    public record Snapshot(
            String model,
            int outputTokens,
            int inputTokens,
            long elapsedMs,
            double tps,
            Instant capturedAt
    ) {}

    private final String modelName;
    private final AtomicReference<Snapshot> lastSnapshot = new AtomicReference<>();

    public OllamaMetricsScheduler(@Value("${app.ai.llm.model-name}") String llmModelName) {
        this.modelName = llmModelName;
    }

    /**
     * Called by PaSSController#onCompleteResponse after every streaming turn.
     *
     * @param outputTokens tokens generated in the assistant reply
     * @param inputTokens  prompt tokens sent to the model
     * @param elapsedMs    wall-clock ms from request received to stream complete
     */
    public void record(int outputTokens, int inputTokens, long elapsedMs) {
        double tps = elapsedMs > 0 ? outputTokens / (elapsedMs / 1_000.0) : 0.0;
        Snapshot snap = new Snapshot(modelName, outputTokens, inputTokens, elapsedMs, tps, Instant.now());
        lastSnapshot.set(snap);
        log.info("[Ollama metrics] model={} | out={} tokens | in={} tokens | {:.2f} t/s | wall={}ms",
                modelName, outputTokens, inputTokens, tps, elapsedMs);
    }

    /** Periodically re-logs the last real snapshot so ops have a rolling heartbeat in the log stream. */
    @Scheduled(fixedDelay = 30_000)
    public void reportLastSnapshot() {
        Snapshot snap = lastSnapshot.get();
        if (snap == null) {
            log.info("[Ollama metrics] No requests processed yet.");
            return;
        }
        long ageSeconds = Instant.now().getEpochSecond() - snap.capturedAt().getEpochSecond();
        log.info("[Ollama metrics] Last request — model={} | out={} tokens | in={} tokens | {:.2f} t/s | wall={}ms | {}s ago",
                snap.model(), snap.outputTokens(), snap.inputTokens(), snap.tps(), snap.elapsedMs(), ageSeconds);
    }
}
