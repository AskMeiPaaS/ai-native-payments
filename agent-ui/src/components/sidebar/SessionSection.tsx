'use client';

import React, { useMemo, useState, useRef, useEffect } from 'react';
import { Copy, Check } from 'lucide-react';

interface SessionSectionProps {
  sessionId: string;
  onNewChat?: () => void;
}

export default function SessionSection({ sessionId, onNewChat }: SessionSectionProps) {
  const [copied, setCopied] = useState(false);
  const copyTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current);
    };
  }, []);

  const displaySessionId = useMemo(() => {
    if (!sessionId || sessionId === 'N/A') return 'Not initialized';
    return sessionId;
  }, [sessionId]);

  const handleCopySessionId = () => {
    navigator.clipboard.writeText(sessionId).then(() => {
      setCopied(true);
      if (copyTimeoutRef.current) clearTimeout(copyTimeoutRef.current);
      copyTimeoutRef.current = setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <>
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
    </>
  );
}
