// k6 load test: k6 run [-e BASE=<server url>] loadtest/search.js
// The API allows 10 requests/s per IP, so a single machine mostly gets 429 responses from a
// public deployment. For latency numbers, run against a local server started with
// SEARCHFORGE_RATE_LIMIT=10000.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.BASE || "http://localhost:8090";
const QUERIES = ["porter stemmer", "page rank", "crawler politeness", "inverted index",
  "bm25 OR tfidf", "\"external merge\"", "robots txt", "variable byte", "bloom filter",
  "spell correction"];

export const options = {
  scenarios: {
    steady: {
      executor: "constant-arrival-rate", rate: 50, timeUnit: "1s", duration: "60s",
      preAllocatedVUs: 20, maxVUs: 50
    },
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
