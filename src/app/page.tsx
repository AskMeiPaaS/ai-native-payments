'use client';

import { useChat } from '@ai-sdk/react';
import { useTelemetry } from '@/hooks/useTelemetry';
import { ShieldCheck, AlertTriangle, UserCog } from 'lucide-react';

export default function PaSSOrchestrator() {
  const { getTelemetryVector } = useTelemetry();
  
  // Connects to a Next.js API route that proxies to our Java LangChain4j backend
  const { messages, input, handleInputChange, handleSubmit, isLoading } = useChat({
    api: '/api/orchestrate',
  });

  const onSubmitWithTelemetry = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const telemetry = getTelemetryVector();
    
    // We attach the telemetry as a custom header or stringified context so the Java backend 
    // can pass it to the Context Agent for Vector Search.
    handleSubmit(e, {
      data: { telemetry: JSON.stringify(telemetry) }
    });
  };

  return (
    <div className="flex flex-col h-screen bg-gray-50 font-sans">
      <header className="bg-blue-900 text-white p-4 flex justify-between items-center shadow-md">
        <h1 className="text-xl font-bold">ai-native-payments: PaSS</h1>
        <div className="flex items-center gap-2 text-sm bg-blue-800 px-3 py-1 rounded-full">
          <ShieldCheck size={16} className="text-green-400" /> Continuous Auth Active
        </div>
      </header>

      <main className="flex-1 overflow-y-auto p-6 space-y-4 max-w-3xl mx-auto w-full">
        {messages.map((m) => (
          <div key={m.id} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[80%] p-4 rounded-xl shadow-sm ${
              m.role === 'user' ? 'bg-blue-600 text-white rounded-br-none' : 'bg-white border border-gray-200 rounded-bl-none text-gray-800'
            }`}>
              
              {/* If the AI returns an XAI log, render a Generative UI Transparency Card */}
              {m.role === 'assistant' && m.content.includes('ESCALATED') && (
                <div className="mb-3 bg-red-50 border-l-4 border-red-500 p-3 rounded text-sm text-red-800 flex items-start gap-2">
                  <AlertTriangle size={18} className="mt-0.5" />
                  <div>
                    <strong>Human-in-the-Loop Triggered</strong><br/>
                    Behavioral context similarity was too low. Freezing state and transferring to human analyst.
                  </div>
                </div>
              )}

              <p className="whitespace-pre-wrap leading-relaxed">{m.content}</p>
            </div>
          </div>
        ))}
        {isLoading && <div className="text-gray-400 text-sm animate-pulse flex justify-start pl-4">Agent is reasoning...</div>}
      </main>

      <footer className="p-4 bg-white border-t border-gray-200">
        <form onSubmit={onSubmitWithTelemetry} className="max-w-3xl mx-auto flex gap-3 relative">
          <input
            className="flex-1 p-4 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:outline-none shadow-sm"
            value={input}
            placeholder="E.g., Switch my BESCOM mandate from HDFC to ICICI bank..."
            onChange={handleInputChange}
            disabled={isLoading}
          />
          <button 
            type="submit" 
            disabled={isLoading || !input.trim()}
            className="bg-blue-600 text-white px-6 py-4 rounded-lg font-medium hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            Send
          </button>
          
          {/* User-Initiated Override (Human Agency) Escape Hatch */}
          <button type="button" className="absolute right-24 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700 p-2" title="Appeal / Speak to Human">
             <UserCog size={20} />
          </button>
        </form>
      </footer>
    </div>
  );
}