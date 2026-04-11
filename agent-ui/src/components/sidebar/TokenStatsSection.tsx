'use client';

import React, { useState } from 'react';

interface TokenStats {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  elapsedMs: number;
  step1InputTokens?: number;
  step1OutputTokens?: number;
  step2ElapsedMs?: number;
  fraudElapsedMs?: number;
  step3InputTokens?: number;
  step3OutputTokens?: number;
}

interface TokenStatsSectionProps {
  lastTokenStats: TokenStats | null;
}

export default function TokenStatsSection({ lastTokenStats }: TokenStatsSectionProps) {
  const [collapsed, setCollapsed] = useState(false);

  if (!lastTokenStats) {
    return (
      <div className="sidebar-section">
        <div className="sidebar-label">Last Request Tokens</div>
        <div className="token-stats-empty">Send a message to see metrics.</div>
      </div>
    );
  }

  const hasClassify = lastTokenStats.step1InputTokens !== undefined || lastTokenStats.step1OutputTokens !== undefined;
  const hasFraud = lastTokenStats.fraudElapsedMs !== undefined;
  const hasToolExec = lastTokenStats.step2ElapsedMs !== undefined;
  const hasFormat = lastTokenStats.step3InputTokens !== undefined && lastTokenStats.step3InputTokens > 0;

  // Compute step numbers matching the backend pipeline:
  // Step 1 = Classification, Step 2 = Fraud (if present), Step N = Execution, Step N+1 = Formatting
  const fraudStep = hasFraud ? 2 : null;
  const execStep = hasFraud ? 3 : 2;
  const fmtStep = execStep + 1;

  return (
    <div className="sidebar-section">
      <button className="sidebar-label sidebar-label--toggle" onClick={() => setCollapsed((c) => !c)}>
        <span>{collapsed ? '▸' : '▾'} Last Request Tokens</span>
      </button>

      {collapsed ? null : <>
      {/* ── Step 1 · Intent Classification ── */}
      {hasClassify && (
        <div className="token-subsection">
          <div className="token-subsection__header">Step 1 · Intent Classification</div>
          <div className="token-stats-grid">
            <div className="token-stat">
              <span className="token-stat__label">In</span>
              <span className="token-stat__value">{lastTokenStats.step1InputTokens ?? 0}</span>
            </div>
            <div className="token-stat">
              <span className="token-stat__label">Out</span>
              <span className="token-stat__value">{lastTokenStats.step1OutputTokens ?? 0}</span>
            </div>
          </div>
        </div>
      )}

      {/* ── Step 2 · Fraud Detection ── */}
      {hasFraud && (
        <div className="token-subsection">
          <div className="token-subsection__header">Step {fraudStep} · Fraud Detection</div>
          <div className="token-stats-grid">
            <div className="token-stat" style={{ gridColumn: 'span 2' }}>
              <span className="token-stat__label">Elapsed</span>
              <span className="token-stat__value">{(lastTokenStats.fraudElapsedMs! / 1000).toFixed(1)}s</span>
            </div>
          </div>
        </div>
      )}

      {/* ── Step N · Tool Execution ── */}
      {hasToolExec && (
        <div className="token-subsection">
          <div className="token-subsection__header">Step {execStep} · Tool Execution</div>
          <div className="token-stats-grid">
            <div className="token-stat" style={{ gridColumn: 'span 2' }}>
              <span className="token-stat__label">Elapsed</span>
              <span className="token-stat__value">{(lastTokenStats.step2ElapsedMs! / 1000).toFixed(1)}s</span>
            </div>
          </div>
        </div>
      )}

      {/* ── Step N+1 · Response Formatting ── */}
      {hasFormat && (
        <div className="token-subsection">
          <div className="token-subsection__header">Step {fmtStep} · Response Formatting</div>
          <div className="token-stats-grid">
            <div className="token-stat">
              <span className="token-stat__label">In</span>
              <span className="token-stat__value">{lastTokenStats.step3InputTokens}</span>
            </div>
            <div className="token-stat">
              <span className="token-stat__label">Out</span>
              <span className="token-stat__value">{lastTokenStats.step3OutputTokens ?? 0}</span>
            </div>
          </div>
        </div>
      )}

      {/* ── Totals ── */}
      <div className="token-subsection">
        <div className="token-subsection__header">📊 Totals</div>
        <div className="token-stats-grid">
          <div className="token-stat">
            <span className="token-stat__label">Tokens</span>
            <span className="token-stat__value">{lastTokenStats.totalTokens || lastTokenStats.inputTokens + lastTokenStats.outputTokens}</span>
          </div>
          <div className="token-stat">
            <span className="token-stat__label">t/s</span>
            <span className="token-stat__value token-stat__value--accent">
              {lastTokenStats.elapsedMs > 0
                ? (lastTokenStats.outputTokens / (lastTokenStats.elapsedMs / 1000)).toFixed(1)
                : '—'}
            </span>
          </div>
        </div>
      </div>
      </>}
    </div>
  );
}
