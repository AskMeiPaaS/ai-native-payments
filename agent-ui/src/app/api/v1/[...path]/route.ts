export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

async function proxy(request: Request, path: string[]) {
  const backendUrl = process.env.JAVA_BACKEND_URL || 'http://localhost:8080';
  console.log(`[API Proxy] JAVA_BACKEND_URL env: ${process.env.JAVA_BACKEND_URL}`);
  console.log(`[API Proxy] Using backend URL: ${backendUrl}`);
  console.log(`[API Proxy] Request path: /api/v1/${path.join('/')}`);
  
  const targetUrl = `${backendUrl}/api/v1/${path.join('/')}`;

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

  try {
    const upstream = await fetch(targetUrl, init);
    return new Response(upstream.body, {
      status: upstream.status,
      headers: upstream.headers,
    });
  } catch (error) {
    console.error(`[API Proxy] Error proxying to ${targetUrl}: ${error}`);
    throw error;
  }
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
