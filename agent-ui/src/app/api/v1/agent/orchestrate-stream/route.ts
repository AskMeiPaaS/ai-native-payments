/**
 * Streaming SSE proxy — forwards the request to the Java backend and
 * pipes the SSE stream back to the browser without buffering.
 *
 * Next.js rewrites() buffer responses, which breaks Server-Sent Events.
 * This API route takes precedence over the rewrite and uses ReadableStream
 * to forward SSE events in real-time.
 */
export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(req: Request) {
  const body = await req.json();
  const backendUrl = process.env.JAVA_BACKEND_URL || 'http://localhost:8080';

  const upstream = await fetch(
    `${backendUrl}/api/v1/agent/orchestrate-stream`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      // 10-minute timeout — Ollama on slow CPU can take several minutes
      signal: AbortSignal.timeout(10 * 60 * 1000),
    }
  );

  if (!upstream.ok || !upstream.body) {
    return new Response(
      JSON.stringify({ error: `Backend returned ${upstream.status}` }),
      { status: upstream.status, headers: { 'Content-Type': 'application/json' } }
    );
  }

  // Pipe the SSE stream straight through without buffering
  return new Response(upstream.body, {
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no', // Disable nginx buffering if present
    },
  });
}
