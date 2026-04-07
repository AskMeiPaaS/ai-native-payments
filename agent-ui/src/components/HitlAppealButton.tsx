'use client';

import React, { useEffect, useRef, useState } from 'react';
import { AlertCircle, Loader, CheckCircle, Frown } from 'lucide-react';

interface HitlAppealButtonProps {
  sessionId: string;
  onAppealSubmitted?: () => void;
}

/**
 * HitlAppealButton
 * 
 * Component for users to appeal AI decisions.
 * Users can click to request human review of a decision.
 * 
 * Usage in chat:
 * <HitlAppealButton sessionId={currentSession.id} />
 */
export default function HitlAppealButton({ sessionId, onAppealSubmitted }: HitlAppealButtonProps) {
  const [showModal, setShowModal] = useState(false);
  const [appealReason, setAppealReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const closeTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (closeTimeoutRef.current) {
        clearTimeout(closeTimeoutRef.current);
      }
    };
  }, []);

  const handleSubmitAppeal = async () => {
    if (!appealReason.trim()) {
      setError('Please provide a reason for your appeal');
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const response = await fetch('/api/v1/agent/appeal', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sessionId,
          appealReason: appealReason.trim(),
        }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Failed to submit appeal');
      }

      setSubmitted(true);
      setAppealReason('');
      onAppealSubmitted?.();

      closeTimeoutRef.current = setTimeout(() => {
        setShowModal(false);
        setSubmitted(false);
      }, 3000);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Error submitting appeal');
    } finally {
      setLoading(false);
    }
  };

  if (!showModal) {
    return (
      <button onClick={() => setShowModal(true)} className="appeal-trigger">
        <Frown size={14} />
        Appeal / Speak to Human
      </button>
    );
  }

  return (
    <div className="appeal-overlay" onClick={(e) => { if (e.target === e.currentTarget) { setShowModal(false); setAppealReason(''); setError(null); } }}>
      <div className="appeal-modal">
        <div className="appeal-modal__header">
          <div className="appeal-modal__title">Appeal AI Decision</div>
          <div className="appeal-modal__subtitle">Request human review of this decision</div>
        </div>

        <div className="appeal-modal__body">
          {submitted ? (
            <div className="appeal-success">
              <CheckCircle size={48} style={{ color: 'var(--success)' }} />
              <div className="appeal-success__title">Appeal Recorded</div>
              <p className="appeal-success__text">
                Your appeal has been submitted. A human operator will review your case shortly.
              </p>
              <div className="appeal-success__id">Session: {sessionId}</div>
            </div>
          ) : (
            <>
              <div>
                <label className="appeal-label">Why do you want to appeal this decision?</label>
                <textarea
                  className={`appeal-textarea${error ? ' has-error' : ''}`}
                  value={appealReason}
                  onChange={e => { setAppealReason(e.target.value); setError(null); }}
                  placeholder="Tell us why you disagree with this decision..."
                  rows={4}
                  maxLength={500}
                />
                <div className="appeal-char-count">{appealReason.length}/500</div>
              </div>

              {error && (
                <div className="appeal-error-box">
                  <AlertCircle size={15} style={{ flexShrink: 0, marginTop: 1 }} />
                  <span>{error}</span>
                </div>
              )}

              <div className="appeal-info-box">
                <strong>💡 What happens next?</strong>
                <ul>
                  <li>Your appeal goes to a human operator</li>
                  <li>They review your case and the AI&apos;s reasoning</li>
                  <li>You&apos;ll receive a manual decision</li>
                </ul>
              </div>

              <div className="appeal-actions">
                <button
                  className="btn btn--ghost btn--full"
                  onClick={() => { setShowModal(false); setAppealReason(''); setError(null); }}
                >
                  Cancel
                </button>
                <button
                  className="btn btn--accent btn--full"
                  onClick={handleSubmitAppeal}
                  disabled={loading}
                >
                  {loading ? <><Loader size={14} className="spin" /> Submitting…</> : 'Submit Appeal'}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
