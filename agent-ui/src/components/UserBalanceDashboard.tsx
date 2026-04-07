'use client';

import React, { useState } from 'react';

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
  lastTransactionType?: string;
  recentTransfers?: RecentTransfer[];
}

interface UserBalanceDashboardProps {
  accountSummary: AccountSummary | null;
  isLoading: boolean;
  error?: string | null;
}

const formatCurrency = (amount: number, currency = 'INR') =>
  new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency,
    maximumFractionDigits: 2,
  }).format(amount ?? 0);

/** Mask email: show first 2 chars + domain, e.g. "ar***@example.com" */
function maskEmail(email: string | undefined): string {
  if (!email) return '—';
  const atIdx = email.indexOf('@');
  if (atIdx <= 0) return '•••';
  const local = email.slice(0, atIdx);
  const domain = email.slice(atIdx);
  const visible = local.slice(0, Math.min(2, local.length));
  return `${visible}${'•'.repeat(Math.max(3, local.length - 2))}${domain}`;
}

/** Mask phone: show last 4 digits, mask the rest, preserving separators */
function maskPhone(phone: string | undefined): string {
  if (!phone) return '—';
  const digits = phone.replace(/\D/g, '');
  if (digits.length < 4) return '•'.repeat(phone.length);
  // Show last 4 digits, mask all other digit positions while preserving non-digit chars
  let digitIdx = 0;
  const totalDigits = digits.length;
  return phone.split('').map((ch) => {
    if (/\d/.test(ch)) {
      digitIdx++;
      return digitIdx > totalDigits - 4 ? ch : '•';
    }
    return ch;
  }).join('');
}

export default function UserBalanceDashboard({
  accountSummary,
  isLoading,
  error,
}: UserBalanceDashboardProps) {
  const [showEmail, setShowEmail] = useState(false);
  const [showPhone, setShowPhone] = useState(false);

  return (
    <div className="balance-widget">
      <div className="balance-header">
        <div className="balance-header-left">
          <div className="balance-title">🏦 {accountSummary?.displayName || 'Customer Dashboard'}</div>
          <div className="balance-subtitle">Use chat to send or receive funds.</div>

          {/* PII fields — shown masked by default, revealed on click */}
          <div className="pii-fields">
            <div className="pii-field">
              <span className="pii-label">📧 Email</span>
              <span className="pii-value">
                {showEmail ? (accountSummary?.email || '—') : maskEmail(accountSummary?.email)}
              </span>
              {accountSummary?.email && (
                <button
                  className="pii-toggle"
                  onClick={() => setShowEmail((v) => !v)}
                  title={showEmail ? 'Hide email' : 'Reveal email'}
                  aria-label={showEmail ? 'Hide email' : 'Reveal email'}
                >
                  {showEmail ? '🙈' : '👁️'}
                </button>
              )}
              <span className="pii-enc-badge" title="Stored encrypted with AES-256-GCM">🔒 QE</span>
            </div>

            <div className="pii-field">
              <span className="pii-label">📱 Phone</span>
              <span className="pii-value">
                {showPhone ? (accountSummary?.phone || '—') : maskPhone(accountSummary?.phone)}
              </span>
              {accountSummary?.phone && (
                <button
                  className="pii-toggle"
                  onClick={() => setShowPhone((v) => !v)}
                  title={showPhone ? 'Hide phone' : 'Reveal phone'}
                  aria-label={showPhone ? 'Hide phone' : 'Reveal phone'}
                >
                  {showPhone ? '🙈' : '👁️'}
                </button>
              )}
              <span className="pii-enc-badge" title="Stored encrypted with AES-256-GCM">🔒 QE</span>
            </div>
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
          <div className="balance-card__label">
            {accountSummary?.lastTransactionType === 'CREDIT' ? 'Last credit' : 'Last debit'}
          </div>
          <div className="balance-card__value">
            {accountSummary && accountSummary.lastTransferAmount > 0
              ? formatCurrency(accountSummary.lastTransferAmount, accountSummary.currency)
              : '—'}
          </div>
          <div className="balance-card__sub">{accountSummary?.lastTransferStatus || 'NO_TRANSFERS_YET'}</div>
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
                  <div className="txn-status">{transfer.status}</div>
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

