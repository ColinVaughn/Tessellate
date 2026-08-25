import { rateLimitBucket } from "./index.ts";

Deno.test("rate-limit buckets hide and separate source addresses", async () => {
  const first = await rateLimitBucket(new Request("https://example.invalid", {
    headers: { "x-forwarded-for": "192.0.2.1" },
  }), "test-secret");
  const repeated = await rateLimitBucket(new Request("https://example.invalid", {
    headers: { "x-forwarded-for": "192.0.2.1" },
  }), "test-secret");
  const second = await rateLimitBucket(new Request("https://example.invalid", {
    headers: { "x-forwarded-for": "192.0.2.2" },
  }), "test-secret");

  if (!/^[0-9a-f]{64}$/.test(first) || first !== repeated || first === second) {
    throw new Error("source addresses were not bucketed safely");
  }
});
