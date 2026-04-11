'use client';

import React from 'react';

interface ActivityLog {
  id: string;
  stage: string;
  detail: string;
  tone?: 'info' | 'success' | 'warn' | 'error';
  time: string;
}

interface ActivitySectionProps {
  activityLogs: ActivityLog[];
}

export default function ActivitySection({ activityLogs }: ActivitySectionProps) {
  return (
    <div className="sidebar-section">
      <div className="sidebar-label">Latest Activity</div>
      <div className="activity-scroll">
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
    </div>
  );
}
