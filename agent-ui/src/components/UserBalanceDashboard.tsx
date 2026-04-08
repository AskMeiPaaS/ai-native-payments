'use client';

import React, { useState, useCallback } from 'react';

interface RecentTransfer {
  id: string;
  amount: number;
  merchantId?: string;
  targetBank?: string;
  transactionType?: string;
  sourceAccount?: string;
  targetAccount?: string;
  status: string;
  createdAt?: string;
  channel?: string;
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
  lastPaymentMethod?: string;
  lastTransactionType?: string;
  recentTransfers?: RecentTransfer[];
}

interface PiiRevealState {
  revealed: boolean;
  loading: boolean;
  value: string | null;
}

const INITIAL_REVEAL: PiiRevealState = { revealed: false, loading: false, value: null };

interface UserBalanceDashboardProps {
  accountSummary: AccountSummary | null;
  isLoading: boolean;
  error?: string | null;
}

const formatTimestamp = (ts?: string) => {
  if (!ts) return null;
  const d = new Date(ts);
  if (isNaN(d.getTime())) return null;
  return d.toLocaleString('en-IN', {
    day: '2-digit', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  });
};

const formatCurrency = (amount: number, currency = 'INR') =>
  new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency,
    maximumFractionDigits: 2,
  }).format(amount ?? 0);

export default function UserBalanceDashboard({
  accountSummary,
  isLoading,
  error,
}: UserBalanceDashboardProps) {
  const [emailReveal, setEmailReveal] = useState<PiiRevealState>(INITIAL_REVEAL);
  const [phoneReveal, setPhoneReveal] = useState<PiiRevealState>(INITIAL_REVEAL);

  const handleReveal = useCallback(async (field: 'email' | 'phone') => {
    const isEmail = field === 'email';
    const setState = isEmail ? setEmailReveal : setPhoneReveal;
    const current = isEmail ? emailReveal : phoneReveal;

    if (current.revealed) {
      setState(INITIAL_REVEAL);
      return;
    }

    setState(prev => ({ ...prev, loading: true }));
    try {
      const userId = encodeURIComponent(accountSummary?.userId ?? '');
      const res = await fetch(`/api/v1/account/reveal-pii?userId=${userId}&field=${field}`);
      if (!res.ok) { setState(INITIAL_REVEAL); return; }
      const data = await res.json();
      setState({ revealed: true, loading: false, value: data.value ?? '—' });
    } catch {
      setState(INITIAL_REVEAL);
    }
  }, [accountSummary?.userId, emailReveal, phoneReveal]);

  const renderPiiField = (label: string, field: 'email' | 'phone', masked: string | undefined) => {
    const state = field === 'email' ? emailReveal : phoneReveal;
    return (
      <div className="pii-field">
        <span className="pii-label">{label}</span>
        <span className="pii-value">
          {state.loading ? '⏳ …' : state.revealed ? (state.value ?? '—') : (masked ?? '—')}
        </span>
        <span className="pii-enc-badge" title="Stored encrypted — AEAD_AES_256_CBC_HMAC_SHA_512-Deterministic">🔒 QE</span>
        <button
          className="pii-reveal-btn"
          onClick={() => handleReveal(field)}
          disabled={state.loading || !accountSummary}
          title={state.revealed ? 'Click to re-mask' : 'Click to decrypt'}
        >
          {state.loading ? '…' : state.revealed ? '🙈' : '👁️'}
        </button>
      </div>
    );
  };

  return (
    <div className="balance-widget">
      <div className="balance-header">
        <div className="balance-header-left">
          <div className="balance-title">🏦 {accountSummary?.displayName || 'Customer Dashboard'}</div>
          <div className="balance-subtitle">Use chat to send or receive funds.</div>

          {/* PII fields — masked by default, QE-decrypted on click */}
          <div className="pii-fields">
            <div className="pii-field">
              <span className="pii-label">🪪 User ID</span>
              <span className="pii-value">{accountSummary?.userId || '—'}</span>
            </div>
            {renderPiiField('📧 Email', 'email', accountSummary?.email)}
            {renderPiiField('📱 Phone', 'phone', accountSummary?.phone)}
          </div>
        </div>
        <div className="balance-live-badge">{isLoading ? 'Refreshing…' : 'Live balance'}</div>
      </div>

      {error && <div className="balance-error">{error}</div>}

      <div className="balance-cards">
        <div className="balance-card">
          <div className="balance-card__label">Current balance</div>
          <div className="balance-card__value">
            {formatCurrency(accountSummary?.currentBalance ?? 0, accountSummary?.currency)}
          </div>
        </div>
        <div className="balance-card">
          <div className="balance-card__label">Available to transfer</div>
          <div className="balance-card__value">
            {formatCurrency(accountSummary?.availableBalance ?? 0, accountSummary?.currency)}
          </div>
        </div>
        <div className="balance-card">
          <div className="balance-card__label">Last transaction</div>
          <div className={`balance-card__value ${accountSummary?.lastTransactionType === 'CREDIT' ? 'txn-amount--credit' : accountSummary?.lastTransactionType === 'DEBIT' ? 'txn-amount--debit' : ''}`}>
            {accountSummary && accountSummary.lastTransferAmount > 0
              ? `${accountSummary.lastTransactionType === 'CREDIT' ? '+' : '−'}${formatCurrency(accountSummary.lastTransferAmount, accountSummary.currency)}`
              : '—'}
          </div>
          <div className="balance-card__sub">
            {accountSummary?.lastPaymentMethod && accountSummary.lastPaymentMethod !== '—'
              ? `${accountSummary.lastPaymentMethod} · ${accountSummary.lastTransferStatus || 'NO_TRANSFERS_YET'}`
              : accountSummary?.lastTransferStatus || 'NO_TRANSFERS_YET'}
          </div>
        </div>
      </div>

      <div>
        <div className="txn-section-label">
          Recent transactions ({accountSummary?.recentTransfers?.length || 0})
        </div>
        {accountSummary?.recentTransfers?.length ? (
          <div className="txn-list">
            {accountSummary.recentTransfers.map((transfer) => (
              <div key={transfer.id} className="txn-item">
                <div className="txn-info">
                  <div className="txn-label">
                    {transfer.sourceAccount || transfer.merchantId || 'Account'}
                    {' → '}
                    {transfer.targetAccount || transfer.merchantId || 'Account'}
                    {transfer.targetBank ? ` · ${transfer.targetBank}` : ''}
                  </div>
                  <div className="txn-id">{transfer.id}</div>
                </div>
                <div className="txn-right">
                  <div className={`txn-amount ${transfer.transactionType === 'CREDIT' ? 'txn-amount--credit' : 'txn-amount--debit'}`}>
                    {transfer.transactionType === 'CREDIT' ? '+' : '−'}
                    {formatCurrency(transfer.amount, accountSummary.currency)}
                  </div>
                  <div className="txn-status">
                    {transfer.status}
                    {transfer.channel && <span className="txn-channel">{transfer.channel}</span>}
                  </div>
                  {formatTimestamp(transfer.createdAt) && (
                    <div className="txn-timestamp">{formatTimestamp(transfer.createdAt)}</div>
                  )}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="txn-empty">No transactions yet. Use the chat to send or receive funds.</div>
        )}
      </div>
    </div>
  );
}

