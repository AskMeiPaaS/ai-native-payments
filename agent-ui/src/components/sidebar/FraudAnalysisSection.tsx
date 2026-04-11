'use client';

import React, { useState } from 'react';

interface BehavioralScore {
  riskScore: number;
  behavioralScore: number;
  action: string;
  signals: string[];
}

interface FraudAnalysisSectionProps {
  lastBehavioralScore: BehavioralScore | null;
}

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

export default function FraudAnalysisSection({ lastBehavioralScore }: FraudAnalysisSectionProps) {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className="sidebar-section">
      <button className="sidebar-label sidebar-label--toggle" onClick={() => setCollapsed((c) => !c)}>
        <span>{collapsed ? '▸' : '▾'} Last Transaction Fraud Analysis</span>
      </button>
      {collapsed ? null : lastBehavioralScore ? (
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
  );
}
