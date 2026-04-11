'use client';

import React from 'react';
import SessionSection from './sidebar/SessionSection';
import ActivitySection from './sidebar/ActivitySection';
import TokenStatsSection from './sidebar/TokenStatsSection';
import FraudAnalysisSection from './sidebar/FraudAnalysisSection';
import ThemeToggle from './sidebar/ThemeToggle';

interface ActivityLog {
  id: string;
  stage: string;
  detail: string;
  tone?: 'info' | 'success' | 'warn' | 'error';
  time: string;
}

interface SidebarProps {
  sessionId?: string;
  activityLogs?: ActivityLog[];
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
  activityLogs = [],
  onNewChat,
  lastTokenStats,
  lastBehavioralScore,
}: SidebarProps) {
  return (
    <aside className="sidebar">
      <SessionSection sessionId={sessionId} onNewChat={onNewChat} />
      <ActivitySection activityLogs={activityLogs} />
      <TokenStatsSection lastTokenStats={lastTokenStats ?? null} />
      <FraudAnalysisSection lastBehavioralScore={lastBehavioralScore ?? null} />
      <ThemeToggle />
    </aside>
  );
}
