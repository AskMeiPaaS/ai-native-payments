'use client';

import React, { useMemo, useState, useEffect } from 'react';
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
}

export default function Sidebar({
  sessionId = 'N/A',
  messageCount = 0,
  activityLogs = [],
}: SidebarProps) {
  const [theme, setTheme] = useState<'light' | 'dark'>('dark');
  const [copied, setCopied] = useState(false);

  const handleCopySessionId = () => {
    navigator.clipboard.writeText(sessionId).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  useEffect(() => {
    // Check system preference
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const savedTheme = localStorage.getItem('theme') as 'light' | 'dark' | null;
    const initialTheme = savedTheme || (prefersDark ? 'dark' : 'light');
    
    setTheme(initialTheme);
    applyTheme(initialTheme);
  }, []);

  const applyTheme = (newTheme: 'light' | 'dark') => {
    const htmlElement = document.documentElement;
    htmlElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('theme', newTheme);
  };

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
      </div>

      {/* Backend */}
      <div className="sidebar-section">
        <div className="sidebar-label">Backend</div>
        <div className="backend-pill">
          <div className="pulse-dot" />
          <span>PaSS Online</span>
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
