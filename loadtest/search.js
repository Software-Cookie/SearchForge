// k6 load test: k6 run -e BASE=http://localhost:8080 loadtest/search.js
// Keep VUs low against the free host; each VU stays under the 10 req/s per-IP limit only if
// you run it from several machines, so expect 429s when testing from one IP (that is the
// rate limiter working). For latency numbers, test locally with SEARCHFORGE_RATE_LIMIT=10000.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.BASE || "http://localhost:8080";
const QUERIES = ["porter stemmer", "page rank", "crawler politeness", "inverted index",
  "bm25 OR tfidf", "\"external merge\"", "robots txt", "variable byte", "bloom filter",
  "spell correction"];

export const options = {
  scenarios: {
    steady: { executor: "constant-arrival-rate", rate: 50, timeUnit: "1s", duration: "60s",
      preAllocatedVUs: 20, maxVUs: 50 },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<100", "p(99)<150"],
  },
};

export default function () {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const page = 1 + Math.floor(Math.random() * 3);
  const res = http.get(`${BASE}/api/search?q=${encodeURIComponent(q)}&page=${page}`);
  check(res, { "status 200": (r) => r.status === 200 });
  sleep(0.1);
}
