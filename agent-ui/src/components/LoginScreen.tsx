'use client';

import React, { useState, useEffect } from 'react';

interface DemoUser {
  userId: string;
  displayName: string;
}

interface UserProfile {
  userId: string;
  displayName: string;
  email: string;
  phone: string;
  currency: string;
  currentBalance: number;
}

interface LoginScreenProps {
  onLogin: (profile: UserProfile) => void;
}

export default function LoginScreen({ onLogin }: LoginScreenProps) {
  const [users, setUsers] = useState<DemoUser[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loggingIn, setLoggingIn] = useState<string | null>(null);

  useEffect(() => {
    async function fetchUsers() {
      try {
        const res = await fetch('/api/v1/users/list');
        if (!res.ok) throw new Error(`Failed to load users: ${res.status}`);
        const data: DemoUser[] = await res.json();
        setUsers(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Unable to load users');
      } finally {
        setIsLoading(false);
      }
    }
    void fetchUsers();
  }, []);

  async function handleSelectUser(userId: string) {
    setLoggingIn(userId);
    setError(null);
    try {
      const res = await fetch(`/api/v1/users/login?userId=${encodeURIComponent(userId)}`);
      if (!res.ok) throw new Error(`Login failed: ${res.status}`);
      const profile: UserProfile = await res.json();
      onLogin(profile);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed. Please try again.');
      setLoggingIn(null);
    }
  }

  return (
    <div className="login-screen">
      <div className="login-card">
        <div className="login-logo">
          <span className="login-logo__icon">💳</span>
          <h1 className="login-logo__title">PaSS</h1>
          <p className="login-logo__sub">Payment Switching Service</p>
        </div>

        <div className="login-divider" />

        <h2 className="login-heading">Select your account</h2>
        <p className="login-hint">Choose a demo user to log in. No password required.</p>

        {error && <div className="login-error">{error}</div>}

        {isLoading ? (
          <div className="login-loading">Loading demo users…</div>
        ) : (
          <div className="login-user-list">
            {users.map((user) => (
              <button
                key={user.userId}
                className={`login-user-btn${loggingIn === user.userId ? ' login-user-btn--loading' : ''}`}
                onClick={() => handleSelectUser(user.userId)}
                disabled={loggingIn !== null}
              >
                <div className="login-user-avatar">
                  {user.displayName.charAt(0).toUpperCase()}
                </div>
                <div className="login-user-info">
                  <span className="login-user-name">{user.displayName}</span>
                  <span className="login-user-id">{user.userId}</span>
                </div>
                {loggingIn === user.userId && (
                  <span className="login-user-spinner">⏳</span>
                )}
              </button>
            ))}
          </div>
        )}

        <p className="login-footer">
          ⚠️ Demo only — for reference purposes only. Not for production use.
        </p>
      </div>
    </div>
  );
}
