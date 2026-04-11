'use client';

import React, { useMemo, useState, useEffect, useRef } from 'react';
import { Copy, Check } from 'lucide-react';

interface ActivityLog {
  id: string;
  stage: string;
  detail: string;
  tone?: 'info' | 'success' | 'warn' | 'error';
  time: string;
}

interface SidebarProps {
  sessionId?: string;
  messageCount?: number;
  activityLogs?: ActivityLog[];
  backendConnected?: boolean;
  onNewChat?: () => void;
  lastTokenStats?: {
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
  } | null;
  lastBehavioralScore?: {
    riskScore: number;
    behavioralScore: number;
    action: string;
    signals: string[];
  } | null;
}

export default function Sidebar({
  sessionId = 'N/A',
  messageCount = 0,
  activityLogs = [],
  backendConnected = true,
  onNewChat,
  lastTokenStats,
  lastBehavioralScore,
}: SidebarProps) {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    if (typeof window === 'undefined') {
      return 'dark';
    }
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const savedTheme = localStorage.getItem('theme') as 'light' | 'dark' | null;
    return savedTheme || (prefersDark ? 'dark' : 'light');
  });
  const [copied, setCopied] = useState(false);
  const copyTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const applyTheme = (newTheme: 'light' | 'dark') => {
    const htmlElement = document.documentElement;
    htmlElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('theme', newTheme);
  };

  const handleCopySessionId = () => {
    navigator.clipboard.writeText(sessionId).then(() => {
      setCopied(true);
      if (copyTimeoutRef.current) {
        clearTimeout(copyTimeoutRef.current);
      }
      copyTimeoutRef.current = setTimeout(() => setCopied(false), 2000);
    });
  };

  useEffect(() => {
    applyTheme(theme);

    return () => {
      if (copyTimeoutRef.current) {
        clearTimeout(copyTimeoutRef.current);
      }
    };
  }, [theme]);

  const toggleTheme = () => {
    const newTheme = theme === 'dark' ? 'light' : 'dark';
    setTheme(newTheme);
    applyTheme(newTheme);
  };

  // Show full session ID without truncation
  const displaySessionId = useMemo(() => {
    if (!sessionId || sessionId === 'N/A') return 'Not initialized';
    return sessionId;
  }, [sessionId]);

  // Helper functions for behavioral score styling
  const getRiskColor = (score: number): string => {
    if (score >= 0.95) return 'success';
    if (score >= 0.80) return 'warning';
    return 'error';
  };

  const getActionColor = (action: string): string => {
    switch (action.toUpperCase()) {
      case 'APPROVE': return 'success';
      case 'MONITOR': return 'warning';
      case 'ESCALATE': return 'warning';
      case 'BLOCK': return 'error';
      default: return 'neutral';
    }
  };

  return (
    <aside className="sidebar">
      {/* Session */}
      <div className="sidebar-section">
        <div className="sidebar-label">Session</div>
        <div className="session-box">
          <button
            onClick={handleCopySessionId}
            title="Click to copy session ID"
            className="session-id-btn"
          >
            <code className="session-id-code">{displaySessionId}</code>
            {copied ? (
              <Check size={12} style={{ flexShrink: 0, color: 'var(--success)' }} />
            ) : (
              <Copy size={12} style={{ flexShrink: 0 }} />
            )}
          </button>
        </div>
      </div>

      {/* New Chat */}
      {onNewChat && (
        <div className="sidebar-section">
          <button
            className="theme-btn"
            onClick={onNewChat}
            title="Clear chat history and start a new conversation"
            aria-label="New chat"
          >
            <span>🗑️</span>
            <span>New Chat</span>
          </button>
        </div>
      )}

      {/* Activity */}
      <div className="sidebar-section">
        <div className="sidebar-label">Latest Activity</div>
        {activityLogs.length === 0 ? (
          <div className="activity-item activity-item--info">
            <span className="activity-detail">Only the most recent message flow is shown here.</span>
          </div>
        ) : (
          activityLogs.map((log) => (
            <div key={log.id} className={`activity-item activity-item--${log.tone ?? 'info'}`}>
              <div className="activity-row">
                <span className="activity-stage">{log.stage}</span>
                <span className="activity-time">{log.time}</span>
              </div>
              <div className="activity-detail">{log.detail}</div>
            </div>
          ))
        )}
      </div>

      {/* Stats */}
      <div className="sidebar-section">
        <div className="sidebar-label">Stats</div>
        <div className="stat-box">
          <span className="stat-label">Messages sent</span>
          <span className="stat-value">{messageCount}</span>
        </div>

        {/* Token usage from last request */}
        <div className="sidebar-label" style={{ marginTop: 'var(--space-3)' }}>Last Request Tokens</div>
        {lastTokenStats ? (
          <div className="token-stats-grid">
            {(lastTokenStats.step1InputTokens !== undefined || lastTokenStats.step1OutputTokens !== undefined) && (
              <>
                <div className="token-stat">
                  <span className="token-stat__label">Classify In</span>
                  <span className="token-stat__value">{lastTokenStats.step1InputTokens ?? 0}</span>
                </div>
                <div className="token-stat">
                  <span className="token-stat__label">Classify Out</span>
                  <span className="token-stat__value">{lastTokenStats.step1OutputTokens ?? 0}</span>
                </div>
              </>
            )}
            {lastTokenStats.fraudElapsedMs !== undefined && (
              <div className="token-stat" style={{ gridColumn: 'span 2' }}>
                <span className="token-stat__label">Fraud Analysis</span>
                <span className="token-stat__value">{(lastTokenStats.fraudElapsedMs / 1000).toFixed(1)}s</span>
              </div>
            )}
            {lastTokenStats.step2ElapsedMs !== undefined && (
              <div className="token-stat" style={{ gridColumn: 'span 2' }}>
                <span className="token-stat__label">Tool Exec</span>
                <span className="token-stat__value">{(lastTokenStats.step2ElapsedMs / 1000).toFixed(1)}s</span>
              </div>
            )}
            {(lastTokenStats.step3InputTokens !== undefined && lastTokenStats.step3InputTokens > 0) && (
              <>
                <div className="token-stat">
                  <span className="token-stat__label">Format In</span>
                  <span className="token-stat__value">{lastTokenStats.step3InputTokens}</span>
                </div>
                <div className="token-stat">
                  <span className="token-stat__label">Format Out</span>
                  <span className="token-stat__value">{lastTokenStats.step3OutputTokens ?? 0}</span>
                </div>
              </>
            )}
            <div className="token-stat">
              <span className="token-stat__label">Total</span>
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
        ) : (
          <div className="token-stats-empty">Send a message to see metrics.</div>
        )}

        {/* Behavioral scoring from last transaction */}
        <div className="sidebar-label" style={{ marginTop: 'var(--space-3)' }}>Last Transaction Fraud Analysis</div>
        {lastBehavioralScore ? (
          <div className="behavioral-stats-grid">
            <div className="behavioral-stat">
              <span className="behavioral-stat__label">Risk Score</span>
              <span className={`behavioral-stat__value behavioral-stat__value--${getRiskColor(lastBehavioralScore.riskScore)}`}>
                {lastBehavioralScore.riskScore.toFixed(2)}
              </span>
            </div>
            <div className="behavioral-stat">
              <span className="behavioral-stat__label">Behavioral Score</span>
              <span className="behavioral-stat__value">{lastBehavioralScore.behavioralScore.toFixed(2)}</span>
            </div>
            <div className="behavioral-stat">
              <span className="behavioral-stat__label">Action</span>
              <span className={`behavioral-stat__value behavioral-stat__value--${getActionColor(lastBehavioralScore.action)}`}>
                {lastBehavioralScore.action}
              </span>
            </div>
            <div className="behavioral-stat behavioral-stat--signals">
              <span className="behavioral-stat__label">Signals</span>
              <span className="behavioral-stat__value">
                {lastBehavioralScore.signals.length > 0 ? lastBehavioralScore.signals.join(', ') : 'None'}
              </span>
            </div>
          </div>
        ) : (
          <div className="behavioral-stats-empty">Complete a transaction to see fraud analysis.</div>
        )}
      </div>

      {/* Backend */}
      <div className="sidebar-section">
        <div className="sidebar-label">Backend</div>
        <div className="backend-pill">
          <div className="pulse-dot" />
          <span>{backendConnected ? 'PaSS Online' : 'PaSS Offline'}</span>
        </div>
      </div>

      {/* Theme toggle */}
      <button
        className="theme-btn"
        onClick={toggleTheme}
        title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
        aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
      >
        <span>{theme === 'dark' ? '☀️' : '🌙'}</span>
        <span>{theme === 'dark' ? 'Light mode' : 'Dark mode'}</span>
      </button>
    </aside>
  );
}
