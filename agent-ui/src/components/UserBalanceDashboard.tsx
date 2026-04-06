'use client';

import React from 'react';

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

export default function UserBalanceDashboard({
  accountSummary,
  isLoading,
  error,
}: UserBalanceDashboardProps) {
  return (
    <div className="balance-widget">
      <div className="balance-header">
        <div>
          <div className="balance-title">🏦 {accountSummary?.displayName || 'Customer Dashboard'}</div>
          <div className="balance-subtitle">Use chat to send or receive funds.</div>
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

