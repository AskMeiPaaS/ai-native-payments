'use client';

import React, { useState, useEffect, useRef } from 'react';
import { AlertCircle, CheckCircle, XCircle, Clock, RefreshCw, Loader } from 'lucide-react';

interface EscalationRecord {
  id: string;
  escalationId?: string;
  sessionId: string;
  reasoning: string;
  status: string;
  createdAt: string;
  operatorId?: string;
  operatorNotes?: string;
  resolvedAt?: string;
  appealSource?: string;
}

interface ActionState {
  escalationId: string;
  action: 'approve' | 'deny' | 'override' | 'cancel';
  status: 'submitting' | 'success' | 'failed';
  timestamp: Date;
  message?: string;
}

/**
 * HitlOperatorDashboard
 * 
 * Dashboard for human operators to manage escalated transactions.
 * Shows pending escalations and allows operators to approve, deny, override, or cancel.
 */
export default function HitlOperatorDashboard() {
  const [escalations, setEscalations] = useState<EscalationRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [operatorId] = useState('operator-' + Date.now());
  const [actionNotes, setActionNotes] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [actionStates, setActionStates] = useState<Map<string, ActionState>>(new Map());
  const [submittingActions, setSubmittingActions] = useState<Set<string>>(new Set());
  const timeoutRefs = useRef<ReturnType<typeof setTimeout>[]>([]);

  // Fetch pending escalations
  useEffect(() => {
    fetchPendingEscalations();
    const interval = setInterval(fetchPendingEscalations, 10000); // Refresh every 10s
    return () => {
      clearInterval(interval);
      timeoutRefs.current.forEach((id) => clearTimeout(id));
      timeoutRefs.current = [];
    };
  }, []);

  const fetchPendingEscalations = async () => {
    try {
      const response = await fetch('/api/v1/operator/escalations/pending', { cache: 'no-store' });
      if (!response.ok) {
        throw new Error(`Failed to fetch escalations (${response.status})`);
      }

      const rawBody = await response.text();
      const data = rawBody.trim() ? JSON.parse(rawBody) : [];
      setEscalations(Array.isArray(data) ? data : []);
      setError(null);
    } catch (err) {
      setEscalations([]);
      setError(err instanceof Error ? err.message : 'Error fetching escalations');
    } finally {
      setLoading(false);
    }
  };

  const handleAction = async (escalationId: string, action: 'approve' | 'deny' | 'override' | 'cancel') => {
    const actionKey = `${escalationId}-${action}`;
    const actionLabelMap: Record<typeof action, string> = {
      approve: 'approved',
      deny: 'denied',
      override: 'overridden',
      cancel: 'cancelled',
    };
    
    // Set submitting state
    setError(null);
    setSubmittingActions(prev => new Set(prev).add(actionKey));
    
    try {
      const response = await fetch(`/api/v1/operator/escalations/${encodeURIComponent(escalationId)}/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          operatorId,
          operatorNotes: actionNotes,
        }),
      });

      if (!response.ok) {
        let errorMessage = `Failed to ${action} escalation`;
        try {
          const errorBody = await response.json();
          if (errorBody?.message) {
            errorMessage = errorBody.message;
          }
        } catch {
          // ignore non-JSON error body
        }
        throw new Error(errorMessage);
      }

      // Track successful action
      setActionStates(prev => new Map(prev).set(actionKey, {
        escalationId,
        action,
        status: 'success',
        timestamp: new Date(),
        message: `✅ Escalation ${actionLabelMap[action]} successfully`,
      }));

      setSuccessMessage(`✅ Escalation ${actionLabelMap[action]} successfully`);
      setActionNotes('');
      setSelectedId(null);
      await fetchPendingEscalations();

      const successTimeout = setTimeout(() => setSuccessMessage(null), 3000);
      timeoutRefs.current.push(successTimeout);

      const actionCleanupTimeout = setTimeout(() => {
        setActionStates(prev => {
          const updated = new Map(prev);
          updated.delete(actionKey);
          return updated;
        });
      }, 5000);
      timeoutRefs.current.push(actionCleanupTimeout);
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : `Error performing ${action}`;
      
      // Track failed action
      setActionStates(prev => new Map(prev).set(actionKey, {
        escalationId,
        action,
        status: 'failed',
        timestamp: new Date(),
        message: errorMsg,
      }));
      
      setError(errorMsg);
    } finally {
      setSubmittingActions(prev => {
        const updated = new Set(prev);
        updated.delete(actionKey);
        return updated;
      });
    }
  };

  const formatTime = (date: Date) => {
    return date.toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: true,
    });
  };

  const getActionStatus = (escalationId: string, action: 'approve' | 'deny' | 'override' | 'cancel') => {
    return actionStates.get(`${escalationId}-${action}`);
  };

  if (loading) {
    return (
      <div className="loading-center operator-wrap">
        <div className="loading-spinner-lg" />
        <p>Loading escalations…</p>
      </div>
    );
  }

  const pendingCount  = escalations.filter(e => e.status.includes('PENDING')).length;
  const resolvedCount = escalations.filter(e => e.status.includes('RESOLVED')).length;

  return (
    <div className="operator-wrap">
      <div className="operator-header">
        <div>
          <div className="operator-title">🚨 HITL Operator Dashboard</div>
          <div className="operator-subtitle">Manage escalations and AI decision appeals</div>
        </div>
        <div className="operator-badge">{operatorId}</div>
      </div>

      <div className="operator-body">
        {successMessage && (
          <div className="alert-banner alert-banner--success">
            <CheckCircle size={18} />
            <span>{successMessage}</span>
          </div>
        )}
        {error && (
          <div className="alert-banner alert-banner--error">
            <AlertCircle size={18} />
            <span>{error}</span>
          </div>
        )}

        {/* Stats */}
        <div className="stats-grid">
          <div className="stat-card stat-card--pending">
            <div>
              <div className="stat-card__label">Pending Cases</div>
              <div className="stat-card__num">{pendingCount}</div>
            </div>
            <Clock size={32} className="stat-card__icon" />
          </div>
          <div className="stat-card stat-card--resolved">
            <div>
              <div className="stat-card__label">Resolved Today</div>
              <div className="stat-card__num">{resolvedCount}</div>
            </div>
            <CheckCircle size={32} className="stat-card__icon" />
          </div>
          <div className="stat-card stat-card--total">
            <div>
              <div className="stat-card__label">Total Cases</div>
              <div className="stat-card__num">{escalations.length}</div>
            </div>
            <RefreshCw size={32} className="stat-card__icon" />
          </div>
        </div>

        {/* Escalation list */}
        <div className="escalation-list">
          {escalations.length === 0 ? (
            <div className="empty-escalations">
              <CheckCircle size={48} style={{ color: 'var(--success)' }} />
              <p>No pending escalations — all cases resolved.</p>
            </div>
          ) : (
            escalations.map(escalation => {
              const isPending  = escalation.status.includes('PENDING');
              const isResolved = escalation.status.includes('RESOLVED');
              const isSelected = selectedId === escalation.id;

              return (
                <div key={escalation.id} className="escalation-card">
                  <div
                    className={`escalation-card__header${isSelected ? ' escalation-card__header--active' : ''}`}
                    onClick={() => setSelectedId(isSelected ? null : escalation.id)}
                  >
                    <div className="escalation-card__top">
                      <div className="escalation-card__session">
                        Session: {escalation.sessionId}
                      </div>
                      <div style={{ display: 'flex', gap: 'var(--space-2)', flexShrink: 0 }}>
                        {isPending  && <span className="badge badge--pending">⏳ Pending Review</span>}
                        {isResolved && <span className="badge badge--resolved">✅ Resolved</span>}
                        {!isPending && !isResolved && <span className="badge badge--default">{escalation.status}</span>}
                      </div>
                    </div>
                    <div className="escalation-card__reasoning">{escalation.reasoning}</div>
                    <div className="escalation-card__meta">
                      <span>Created: {new Date(escalation.createdAt).toLocaleString()}</span>
                      {escalation.resolvedAt && <span>Resolved: {new Date(escalation.resolvedAt).toLocaleString()}</span>}
                      {escalation.appealSource === 'USER_INITIATED'
                        ? <span className="source-badge--user">👤 User Appeal</span>
                        : <span className="source-badge--system">🤖 System Escalation</span>}
                      <span>Ref: {escalation.escalationId || escalation.id}</span>
                    </div>
                  </div>

                  {/* Action panel — pending only */}
                  {isSelected && isPending && (
                    <div className="escalation-card__actions">
                      <div>
                        <div className="notes-label">Operator Notes</div>
                        <textarea
                          className="notes-field"
                          value={actionNotes}
                          onChange={e => setActionNotes(e.target.value)}
                          placeholder="Enter your decision notes…"
                          rows={3}
                        />
                      </div>

                      <div className="action-btn-grid">
                        {(['approve', 'deny', 'override', 'cancel'] as const).map(action => {
                          const actionState = getActionStatus(escalation.id, action);
                          const isSubmitting = submittingActions.has(`${escalation.id}-${action}`);
                          const iconMap = {
                            approve: <CheckCircle size={13} />,
                            deny:    <XCircle    size={13} />,
                            override: null,
                            cancel:   null,
                          };
                          return (
                            <div key={action}>
                              <button
                                className={`action-btn action-btn--${action}`}
                                onClick={() => handleAction(escalation.escalationId || escalation.id, action)}
                                disabled={isSubmitting}
                              >
                                {isSubmitting ? <Loader size={13} /> : iconMap[action]}
                                {isSubmitting ? 'Submitting…' : action.charAt(0).toUpperCase() + action.slice(1)}
                              </button>
                              {actionState && (
                                <div className={`action-feedback action-feedback--${actionState.status === 'success' ? 'success' : 'failed'}`}>
                                  {actionState.status === 'success'
                                    ? <CheckCircle size={11} />
                                    : <AlertCircle size={11} />}
                                  <span>{action}ed</span>
                                  <span style={{ opacity: 0.7 }}>{formatTime(actionState.timestamp)}</span>
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* Resolved details */}
                  {isResolved && (
                    <div className="resolved-details">
                      <span><strong>Decision:</strong> {escalation.status.replace('RESOLVED_', '')}</span>
                      {escalation.operatorId    && <span><strong>Operator:</strong> {escalation.operatorId}</span>}
                      {escalation.operatorNotes && <span><strong>Notes:</strong> {escalation.operatorNotes}</span>}
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
