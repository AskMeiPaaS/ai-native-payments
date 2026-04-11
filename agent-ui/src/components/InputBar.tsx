'use client';

import React, { useState, useRef, useEffect } from 'react';

interface InputBarProps {
  onSendMessage: (message: string) => void;
  isLoading?: boolean;
  disabled?: boolean;
  placeholder?: string;
}

export default function InputBar({
  onSendMessage,
  isLoading = false,
  disabled = false,
  placeholder = 'Ask the Payment Switching Service (PaSS) Agent...',
}: InputBarProps) {
  const [message, setMessage] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Auto-resize textarea
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 150) + 'px';
    }
  }, [message]);

  const handleSend = () => {
    if (message.trim() && !isLoading && !disabled) {
      onSendMessage(message.trim());
      setMessage('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !isLoading && !disabled) {
      e.preventDefault();
      handleSend();
    }
  };

  const effectivePlaceholder = disabled
    ? (placeholder !== 'Ask the Payment Switching Service (PaSS) Agent...' ? placeholder : 'PaSS is offline — chat unavailable')
    : placeholder;

  return (
    <div className="input-area">
      <textarea
        ref={textareaRef}
        className="input-field"
        value={message}
        onChange={(e) => setMessage(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={effectivePlaceholder}
        disabled={isLoading || disabled}
        rows={1}
      />
      <button
        className="send-btn"
        onClick={handleSend}
        disabled={isLoading || disabled || !message.trim()}
        title={isLoading ? 'Processing…' : 'Send message (Enter)'}
      >
        {isLoading ? <div className="spinner" /> : <span>→</span>}
      </button>
    </div>
  );
}
