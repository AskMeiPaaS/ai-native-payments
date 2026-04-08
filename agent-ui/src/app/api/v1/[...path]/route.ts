export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

const MAX_RETRIES = 3;
const INITIAL_DELAY_MS = 500;

async function proxy(request: Request, path: string[]) {
  const backendUrl = process.env.JAVA_BACKEND_URL || 'http://localhost:8080';
  const { search } = new URL(request.url);
  console.log(`[API Proxy] Using backend URL: ${backendUrl}`);
  console.log(`[API Proxy] Request path: /api/v1/${path.join('/')}${search}`);

  const targetUrl = `${backendUrl}/api/v1/${path.join('/')}${search}`;

  const headers = new Headers(request.headers);
  headers.delete('host');
  headers.delete('content-length');

  const init: RequestInit = {
    method: request.method,
    headers,
    cache: 'no-store',
    redirect: 'follow',
  };

  if (request.method !== 'GET' && request.method !== 'HEAD') {
    init.body = await request.text();
  }

  let lastError: unknown;
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    try {
      const upstream = await fetch(targetUrl, init);
      return new Response(upstream.body, {
        status: upstream.status,
        headers: upstream.headers,
      });
    } catch (error) {
      lastError = error;
      if (attempt < MAX_RETRIES) {
        const delay = INITIAL_DELAY_MS * Math.pow(2, attempt);
        console.warn(`[API Proxy] Attempt ${attempt + 1} failed for ${targetUrl}, retrying in ${delay}ms...`);
        await new Promise((r) => setTimeout(r, delay));
      }
    }
  }

  console.error(`[API Proxy] All ${MAX_RETRIES + 1} attempts failed for ${targetUrl}:`, lastError);
  return new Response(
    JSON.stringify({ error: 'Backend unavailable', detail: 'api-gateway is not reachable. It may be starting up — please retry shortly.' }),
    { status: 502, headers: { 'Content-Type': 'application/json' } },
  );
}

export async function GET(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  return proxy(request, path);
}

export async function POST(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  return proxy(request, path);
}

export async function PUT(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  return proxy(request, path);
}

export async function PATCH(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  return proxy(request, path);
}

export async function DELETE(request: Request, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  return proxy(request, path);
}
