const encoder = new TextEncoder();

function serviceKey(): string | undefined {
  const keys = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (keys) {
    try {
      return JSON.parse(keys).default;
    } catch {
      return undefined;
    }
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
}

function acceptsPublishableKey(request: Request): boolean {
  const supplied = request.headers.get("apikey");
  if (!supplied) {
    return false;
  }
  const keys = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS");
  try {
    return Boolean(keys && Object.values(JSON.parse(keys)).includes(supplied))
      || Deno.env.get("SUPABASE_ANON_KEY") === supplied;
  } catch {
    return false;
  }
}

function response(status: number, error?: string): Response {
  return new Response(error ? JSON.stringify({ error }) : null, {
    status,
    headers: error ? { "content-type": "application/json" } : undefined,
  });
}

export async function rateLimitBucket(request: Request, secret: string): Promise<string> {
  const address = request.headers.get("cf-connecting-ip")
    ?? request.headers.get("x-forwarded-for")?.split(",", 1)[0]?.trim()
    ?? "unknown";
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const digest = await crypto.subtle.sign("HMAC", key, encoder.encode(address));
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
}

export async function handle(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return new Response(null, { status: 405, headers: { allow: "POST" } });
  }
  if (!acceptsPublishableKey(request)) {
    return response(401, "invalid_api_key");
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const secretKey = serviceKey();
  if (!supabaseUrl || !secretKey) {
    return response(500, "ingestion_unavailable");
  }

  const body = await request.text();
  if (encoder.encode(body).byteLength > 262144) {
    return response(413, "report_too_large");
  }

  let report: unknown;
  try {
    report = JSON.parse(body);
  } catch {
    return response(400, "invalid_report");
  }
  if (!report || typeof report !== "object" || Array.isArray(report)) {
    return response(400, "invalid_report");
  }

  const result = await fetch(
    `${supabaseUrl}/rest/v1/rpc/submit_tessellate_compatibility_report`,
    {
      method: "POST",
      headers: {
        apikey: secretKey,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        p_bucket: await rateLimitBucket(request, secretKey),
        p_report: report,
      }),
    },
  );

  if (result.ok) {
    return response(204);
  }
  const failure = await result.text();
  if (failure.includes("rate_limit_exceeded")) {
    return response(429, "rate_limit_exceeded");
  }
  console.error("compatibility report RPC failed", result.status, failure);
  return response(result.status >= 500 ? 503 : 400, "invalid_report");
}

if (import.meta.main) {
  Deno.serve(handle);
}
