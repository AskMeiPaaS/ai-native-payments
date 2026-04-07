'use client';

import React, { useState } from 'react';
import AgentChatDashboard from '@/components/AgentChatDashboard';
import LoginScreen from '@/components/LoginScreen';

interface UserProfile {
  userId: string;
  displayName: string;
  email: string;
  phone: string;
  currency: string;
  currentBalance: number;
}

export default function Home() {
  const [loggedInUser, setLoggedInUser] = useState<UserProfile | null>(null);

  if (!loggedInUser) {
    return (
      <div className="app-page">
        <LoginScreen onLogin={setLoggedInUser} />
      </div>
    );
  }

  return (
    <div className="app-page">
      <AgentChatDashboard
        userId={loggedInUser.userId}
        userProfile={loggedInUser}
        onLogout={() => setLoggedInUser(null)}
      />
    </div>
  );
}
