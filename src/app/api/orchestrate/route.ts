import { NextResponse } from 'next/server';

// Defines the shape of the request expected from the Java backend
interface AgentRequest {
  sessionId: string;
  userIntent: string;
  telemetryVector: string;
}

export async function POST(req: Request) {
  try {
    const { messages, data } = await req.json();

    // The Vercel AI SDK sends the entire chat history. 
    // We grab the latest user message as the current intent.
    const latestMessage = messages[messages.length - 1];
    
    // Extract the telemetry data we attached in page.tsx
    const telemetry = data?.telemetry || '{}';
    
    // Generate a simple session ID for this demo (in production, use JWT or proper session management)
    const sessionId = "session-" + Math.random().toString(36).substring(2, 9);

    const payload: AgentRequest = {
      sessionId: sessionId,
      userIntent: latestMessage.content,
      telemetryVector: telemetry
    };

    // Forward the request to our Java 25 / Spring Boot backend
    // If running via Docker, this would be http://api-gateway:8080/api/v1/agent/orchestrate
    const javaBackendUrl = process.env.JAVA_BACKEND_URL || 'http://localhost:8080/api/v1/agent/orchestrate';

    const response = await fetch(javaBackendUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      throw new Error(`Java backend returned status: ${response.status}`);
    }

    const javaData = await response.json();

    // The Vercel AI SDK expects a specific response format to render the chat
    return NextResponse.json({
      id: sessionId,
      role: 'assistant',
      content: javaData.reply
    });

  } catch (error: any) {
    console.error("Orchestration Error:", error);
    // Trigger graceful degradation / HITL fallback on the UI if the backend fails
    return NextResponse.json({
      role: 'assistant',
      content: "SYSTEM ERROR: Unable to reach the orchestration engine. Please use the 'Speak to Human' tool to proceed securely."
    }, { status: 500 });
  }
}