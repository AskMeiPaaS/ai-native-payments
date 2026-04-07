'use client';

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Check, CheckCheck, Clock } from 'lucide-react';
import InputBar from './InputBar';
import Sidebar from './Sidebar';
import HitlOperatorDashboard from './HitlOperatorDashboard';
import HitlAppealButton from './HitlAppealButton';
import UserBalanceDashboard from './UserBalanceDashboard';

interface Message {
  id: string;
  role: 'user' | 'agent';
  content: string;
  timestamp: Date;
  status?: 'sending' | 'sent' | 'delivered' | 'failed'; // Message delivery status
  meta?: string;
  channel?: string; // LLM+RAG selected payment channel (e.g. "UPI", "NEFT", "RTGS")
}

/** Canonical channel names the LLM or tool response may mention. */
const CHANNELS = ['UPI LITE', 'UPI', 'NEFT', 'RTGS', 'IMPS', 'CHEQUE', 'CASH'] as const;
type Channel = typeof CHANNELS[number];

/** Extract the payment channel the LLM chose from the completed agent reply. */
function extractChannel(text: string): string | undefined {
  const t = text.toUpperCase();

  // 1. "via <channel>" — the tool SUCCESS string always contains this
  for (const ch of CHANNELS) {
    if (new RegExp(`\\bVIA\\s+${ch}\\b`).test(t)) return ch;
  }
  // 2. "using <channel>" / "through <channel>" / "by <channel>"
  for (const ch of CHANNELS) {
    if (new RegExp(`\\b(?:USING|THROUGH|BY)\\s+${ch}\\b`).test(t)) return ch;
  }
  // 3. "<channel> transfer" / "<channel> payment" / "<channel> channel"
  for (const ch of CHANNELS) {
    if (new RegExp(`\\b${ch}\\s+(?:TRANSFER|PAYMENT|CHANNEL|RAIL)\\b`).test(t)) return ch;
  }
  // 4. Bare channel keyword anywhere in a SUCCESS response
  if (t.includes('SUCCESS')) {
    for (const ch of CHANNELS) {
      if (new RegExp(`\\b${ch}\\b`).test(t)) return ch;
    }
  }
  return undefined;
}

interface ActivityLog {
  id: string;
  stage: string;
  detail: string;
  tone?: 'info' | 'success' | 'warn' | 'error';
  time: string;
}

interface AccountTransferSummary {
  id: string;
  amount: number;
  merchantId?: string;
  targetBank?: string;
  status: string;
  createdAt?: string;
  resultingBalance?: number;
}

interface AccountSummary {
  userId: string;
  displayName: string;
  email?: string;
  phone?: string;
  currentBalance: number;
  availableBalance: number;
  currency: string;
  lastTransferAmount: number;
  lastTransferStatus: string;
  recentTransfers?: AccountTransferSummary[];
}

interface AgentChatDashboardProps {
  backendUrl?: string; // kept for backwards compat but ignored; Next.js rewrites handle routing
  userId?: string;
  userProfile?: {
    userId: string;
    displayName: string;
    email: string;
    phone: string;
    currency: string;
    currentBalance: number;
  };
  onLogout?: () => void;
}

export default function AgentChatDashboard({ userId, onLogout }: AgentChatDashboardProps = {}) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [sessionId, setSessionId] = useState<string>('');
  const [mongoConnected, setMongoConnected] = useState(false);
  const [activeTab, setActiveTab] = useState<'chat' | 'operator'>('chat');
  const [activityLogs, setActivityLogs] = useState<ActivityLog[]>([]);
  const [accountSummary, setAccountSummary] = useState<AccountSummary | null>(null);
  const [accountError, setAccountError] = useState<string | null>(null);
  const [isAccountLoading, setIsAccountLoading] = useState(true);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Resolve effective userId for API calls
  const effectiveUserId = userId || 'demo-user';

  const buildActivityEntry = useCallback((
    stage: string,
    detail: string,
    tone: ActivityLog['tone'] = 'info'
  ): ActivityLog => ({
    id: `log-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    stage,
    detail,
    tone,
    time: new Date().toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }),
  }), []);

  const appendActivity = useCallback((stage: string, detail: string, tone: ActivityLog['tone'] = 'info') => {
    setActivityLogs((prev) => [buildActivityEntry(stage, detail, tone), ...prev].slice(0, 8));
  }, [buildActivityEntry]);

  const resetActivityLog = useCallback((stage: string, detail: string, tone: ActivityLog['tone'] = 'info') => {
    setActivityLogs([buildActivityEntry(stage, detail, tone)]);
  }, [buildActivityEntry]);

  const refreshAccountSummary = useCallback(async (notifyOnBalanceChange = false) => {
    try {
      setIsAccountLoading(true);
      const response = await fetch(`/api/v1/account/dashboard?userId=${encodeURIComponent(effectiveUserId)}`, {
        cache: 'no-store',
      });

      if (!response.ok) {
        throw new Error(`Dashboard API error: ${response.status}`);
      }

      const data: AccountSummary = await response.json();
      setAccountError(null);
      setAccountSummary((prev) => {
        if (notifyOnBalanceChange && prev && data.currentBalance < prev.currentBalance) {
          const deductedAmount = (prev.currentBalance - data.currentBalance).toFixed(2);
          appendActivity('Balance updated', `₹${deductedAmount} deducted after successful transfer.`, 'success');
        }
        return data;
      });
    } catch (error) {
      console.warn('Account dashboard refresh failed:', error);
      setAccountError('Unable to load account balance right now.');
    } finally {
      setIsAccountLoading(false);
    }
  }, [appendActivity, effectiveUserId]);

  // Check backend and MongoDB health
  const checkHealth = useCallback(async () => {
    try {
      const response = await fetch('/api/v1/agent/health');
      if (response.ok) {
        const data = await response.json();
        setMongoConnected(data.mongodbStatus === true);
      } else {
        setMongoConnected(false);
      }
    } catch (error) {
      console.warn('Health check failed:', error);
      setMongoConnected(false);
    }
  }, []);

  // Initialize session on mount
  useEffect(() => {
    const newSessionId = `session-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    setSessionId(newSessionId);
    resetActivityLog('Session ready', 'New chat session initialized.', 'info');

    // Check health immediately
    checkHealth();
    void refreshAccountSummary();

    // Check health every 10 seconds
    const healthInterval = setInterval(checkHealth, 10000);
    return () => clearInterval(healthInterval);
  }, [checkHealth, refreshAccountSummary, resetActivityLog]);

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Send message to backend with streaming
  const handleSendMessage = useCallback(
    async (userMessage: string) => {
      if (!userMessage.trim()) return;

      // Add user message to UI with 'sending' status
      const userMsg: Message = {
        id: `msg-${Date.now()}`,
        role: 'user',
        content: userMessage,
        timestamp: new Date(),
        status: 'sending',
      };

      setMessages((prev) => [...prev, userMsg]);
      setIsLoading(true);

      // Add wait message (agent)
      const waitMsg: Message = {
        id: `msg-${Date.now()}-wait`,
        role: 'agent',
        content: '⏳ Processing...',
        timestamp: new Date(),
        status: 'sent',
      };
      
      setMessages((prev) => [...prev, waitMsg]);
      const waitMsgId = waitMsg.id;
      
      // Create a dedicated ID for the streaming message (will be reused for all chunks)
      const agentMessageId = `msg-${Date.now()}-stream-${Math.random().toString(36).substr(2, 9)}`;
      let timeoutId: ReturnType<typeof setTimeout> | null = null;

      try {
        resetActivityLog('Queued', 'Sending request to the Payment Switching Service (PaSS) orchestrator...', 'info');
        const startTime = performance.now();
        let fullResponse = '';
        let isFirstEvent = true;
        let hasLoggedStreaming = false;

        // Call streaming backend API with long timeout (10 minutes for orchestration)
        const abortController = new AbortController();
        timeoutId = setTimeout(() => {
          abortController.abort();
          console.error('[SSE] Request timeout after 10 minutes');
        }, 10 * 60 * 1000); // 10 minutes
        
        const response = await fetch('/api/v1/agent/orchestrate-stream', {
          method: 'POST',
          signal: abortController.signal,
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            sessionId,
            userIntent: userMessage,
            timestamp: new Date().toISOString(),
          }),
        });
        
        // Clear timeout once response starts
        clearTimeout(timeoutId);
        timeoutId = null;

        if (!response.ok) {
          throw new Error(`API error: ${response.status}`);
        }

        appendActivity('Connected', 'Stream opened successfully.', 'info');

        // Mark user message as 'sent' since the API call succeeded
        setMessages((prev) =>
          prev.map((msg) =>
            msg.role === 'user' && msg.content === userMessage && msg.status === 'sending'
              ? { ...msg, status: 'sent' as const }
              : msg
          )
        );

        const reader = response.body?.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let currentEventName = '';

        if (!reader) throw new Error('No response body');

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            console.log('[SSE] Stream ended');
            break;
          }

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            // Track the current event name across lines.
            // Spring SseEmitter emits `event:start` / `data:{...}` (no space after `:`),
            // so accept both `event:foo` and `event: foo` forms.
            if (line.startsWith('event:')) {
              currentEventName = line.slice(line.indexOf(':') + 1).trim();
              console.log(`[SSE Event Type] ${currentEventName}`);
              continue;
            }

            if (line.startsWith('id:')) {
              console.log(`[SSE ID] ${line.slice(line.indexOf(':') + 1).trim()}`);
              continue;
            }

            // Blank line = end of SSE event block, reset event name
            if (line.trim() === '') {
              currentEventName = '';
              continue;
            }

            // Log all non-empty SSE lines for debugging
            if (line) {
              console.log(`[SSE Raw] ${line.substring(0, 150)}`);
            }

            if (line.startsWith('data:')) {
              try {
                const dataStr = line.slice(line.indexOf(':') + 1).trimStart();
                console.log(`[SSE Data String] ${dataStr.substring(0, 150)}`);
                const jsonData = JSON.parse(dataStr);
                console.log('[SSE Parsed JSON]', jsonData);

                // Heartbeat — keep the wait message updated with elapsed time
                if (currentEventName === 'heartbeat' && jsonData.elapsed !== undefined) {
                  setMessages((prev) =>
                    prev.map((msg) =>
                      msg.id === waitMsgId
                        ? { ...msg, content: `⏳ Thinking... (${jsonData.elapsed}s)` }
                        : msg
                    )
                  );
                  continue;
                }

                if (jsonData.message && isFirstEvent) {
                  console.log('[First Event] Initial message:', jsonData.message);
                  isFirstEvent = false;
                  appendActivity('Processing', jsonData.message, 'info');
                  // Update wait message with initial status
                  // Also mark user message as delivered
                  setMessages((prev) =>
                    prev.map((msg) => {
                      if (msg.id === waitMsgId) {
                        return { ...msg, content: jsonData.message, status: 'sent' as const };
                      }
                      if (msg.role === 'user' && msg.content === userMessage) {
                        return { ...msg, status: 'delivered' as const };
                      }
                      return msg;
                    })
                  );
                }

                if (jsonData.content) {
                  console.log('[Chunk]', jsonData.content);
                  fullResponse += jsonData.content;
                  if (!hasLoggedStreaming) {
                    appendActivity('Streaming', 'Response is arriving from Ollama.', 'success');
                    hasLoggedStreaming = true;
                  }
                  // Update agent message with streaming content using fixed message ID
                  setMessages((prev) => {
                    // Try to find existing streaming message
                    const existing = prev.find((m) => m.id === agentMessageId);
                    if (existing) {
                      // Update existing streaming message
                      return prev.map((msg) =>
                        msg.id === agentMessageId
                          ? { ...msg, content: fullResponse }
                          : msg
                      );
                    } else {
                      // Create new streaming message (first chunk)
                      return [
                        ...prev.filter((m) => m.id !== waitMsgId),
                        {
                          id: agentMessageId,
                          role: 'agent' as const,
                          content: fullResponse,
                          timestamp: new Date(),
                        },
                      ];
                    }
                  });
                }

                if (jsonData.elapsedMs) {
                  console.log('[Completion] Elapsed time:', jsonData.elapsedMs, 'Full response length:', fullResponse.length);
                  const elapsedSecs = (jsonData.elapsedMs / 1000).toFixed(2);
                  const detectedChannel = extractChannel(fullResponse);
                  appendActivity(
                    'Completed',
                    detectedChannel
                      ? `${detectedChannel} channel selected · ${elapsedSecs}s`
                      : (jsonData.message || `Completed in ${elapsedSecs}s`),
                    'success'
                  );
                  setMessages((prev) =>
                    prev.map((msg) =>
                      msg.id === agentMessageId
                        ? {
                            ...msg,
                            content: fullResponse,
                            meta: jsonData.message || `⏱️ Response time: ${elapsedSecs}s`,
                            ...(detectedChannel ? { channel: detectedChannel } : {}),
                          }
                        : msg
                    )
                  );
                  void refreshAccountSummary(true);
                }
              } catch (e) {
                // SSE parsing error, log and continue
                console.error('[SSE Parse Error]', e, 'Line:', line);
              }
            }
          }
        }

        const endTime = performance.now();
        const totalTime = ((endTime - startTime) / 1000).toFixed(2);
        console.log(`✅ Total request time: ${totalTime}s, Response length: ${fullResponse.length} chars`);
      } catch (error) {
        console.error('Failed to send message:', error);
        appendActivity(
          'Failed',
          error instanceof Error ? error.message : 'Unable to communicate with the orchestrator.',
          'error'
        );

        // Replace wait message with error and mark user message as failed
        const errorContent = `Error: Unable to communicate with the orchestrator. ${
          error instanceof Error ? error.message : 'Please try again.'
        }`;

        setMessages((prev) =>
          prev.map((msg) => {
            if (msg.id === waitMsgId) {
              return {
                ...msg,
                content: errorContent,
              };
            }
            // Mark user message as failed if it hasn't been delivered yet
            if (msg.role === 'user' && msg.content === userMessage && msg.status !== 'delivered') {
              return { ...msg, status: 'failed' as const };
            }
            return msg;
          })
        );
      } finally {
        if (timeoutId) {
          clearTimeout(timeoutId);
        }
        setIsLoading(false);
      }
    },
    [appendActivity, refreshAccountSummary, resetActivityLog, sessionId]
  );

  const formatTime = (date: Date) => {
    return date.toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  return (
    <div className="agent-chat-dashboard">
      <Sidebar
        sessionId={sessionId}
        messageCount={messages.filter((m) => m.role === 'user').length}
        activityLogs={activityLogs}
      />

      <div className="chat-main">
        {/* Tab bar */}
        <div className="chat-tab-nav">
          <div className="tab-buttons">
            <button
              className={`tab-btn${activeTab === 'chat' ? ' tab-btn--active' : ''}`}
              onClick={() => setActiveTab('chat')}
            >
              💬 Agent Chat
            </button>
            <button
              className={`tab-btn${activeTab === 'operator' ? ' tab-btn--active' : ''}`}
              onClick={() => setActiveTab('operator')}
            >
              🚨 Operator Dashboard
            </button>
          </div>

          <div className="tab-nav-right">
            <div className="security-badge">
              <span>⚠️</span>
              <span>DEMO ONLY</span>
              <div className="security-badge-tooltip">
                <strong>REFERENCE IMPLEMENTATION — NOT FOR PRODUCTION</strong>
                <p>Demonstration project. Missing critical features:</p>
                <ul>
                  <li>No authentication / authorization</li>
                  <li>No rate limiting or DDoS protection</li>
                  <li>No HTTPS / TLS enforcement</li>
                  <li>No CSRF token protection</li>
                  <li>No real payment processor — mock data only</li>
                </ul>
                <p>For production: add auth, rate limiting, HTTPS, input sanitization, and a licensed payment processor.</p>
              </div>
            </div>
            {onLogout && (
              <button className="logout-btn" onClick={onLogout} title="Switch user">
                🔄 Switch User
              </button>
            )}
          </div>
        </div>

        {/* Chat tab */}
        {activeTab === 'chat' ? (
          <div className="chat-content-area">
            <div className="chat-header">
              <div>
                <h1 className="chat-title">Payment Switching Service (PaSS) Agent</h1>
                <p className="chat-subtitle">Intelligent Payment Processing with AI</p>
              </div>
            </div>

            <UserBalanceDashboard
              accountSummary={accountSummary}
              isLoading={isAccountLoading}
              error={accountError}
            />

            <div className="messages-container">
              {messages.length === 0 ? (
                <div className="empty-state">
                  <span className="empty-state__icon">✨</span>
                  <h2 className="empty-state__title">Welcome to PaSS</h2>
                  <p className="empty-state__text">Start a conversation to interact with the AI Agent</p>
                </div>
              ) : (
                messages.map((msg) => (
                  <div
                    key={msg.id}
                    className={`message-group message-group--${msg.role}`}
                  >
                    <div className={`message-bubble message-bubble--${msg.role}`}>
                      <div style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</div>
                      {msg.role === 'agent' && msg.channel && (
                        <div className="channel-badge" data-ch={msg.channel}>
                          <span className="channel-badge__dot" />
                          <span className="channel-badge__label">{msg.channel}</span>
                          <span className="channel-badge__suffix">via RAG+LLM</span>
                        </div>
                      )}
                      {msg.role === 'agent' && msg.meta && (
                        <div className="message-meta">{msg.meta}</div>
                      )}
                      {msg.role === 'agent' && msg.content && !msg.content.startsWith('⏳') && (
                        <HitlAppealButton sessionId={sessionId} />
                      )}
                    </div>
                    <div className="message-timestamp">
                      <span>{formatTime(msg.timestamp)}</span>
                      {msg.role === 'user' && msg.status === 'sending'   && <Clock size={11} />}
                      {msg.role === 'user' && msg.status === 'sent'      && <Check size={11} />}
                      {msg.role === 'user' && msg.status === 'delivered' && <CheckCheck size={11} style={{ color: 'var(--accent)' }} />}
                      {msg.role === 'user' && msg.status === 'failed'    && <span style={{ color: 'var(--danger)' }}>✕</span>}
                    </div>
                  </div>
                ))
              )}
              <div ref={messagesEndRef} />
            </div>

            <InputBar onSendMessage={handleSendMessage} isLoading={isLoading} />
          </div>
        ) : (
          <HitlOperatorDashboard />
        )}
      </div>
    </div>
  );
}
