// k6 user-journey load test: k6 run -e BASE=http://localhost:8090 -e USERS=1500 loadtest/journey.js
// Each virtual user behaves like a person: opens the page, types (autocomplete), searches the
// live web, sometimes opens page 2, images or the local index, then reads for 5-15 s.
// Queries follow a Zipf-like spread over 5,000 terms: a few are popular, most are rare.
// -e COLD=1 makes nearly every query unique, so each one goes upstream.
// Run it against a local server (started with SEARCHFORGE_RATE_LIMIT=100000) and a stubbed
// upstream (loadtest/upstream_stub.py), never against production or real search APIs.
import http from "k6/http";
import { check, sleep, group } from "k6";

const BASE = __ENV.BASE || "http://localhost:8090";
const USERS = parseInt(__ENV.USERS || "100");
const HOLD = __ENV.HOLD || "3m";
const COLD = __ENV.COLD === "1"; // worst case: almost every search is new (cache misses)

export const options = {
  scenarios: {
    users: {
      executor: "ramping-vus", startVUs: 0,
      stages: [{ duration: "1m", target: USERS }, { duration: HOLD, target: USERS },
        { duration: "20s", target: 0 }],
      gracefulRampDown: "20s",
    },
  },
  thresholds: {
    "http_req_failed": ["rate<0.001"],
    "http_req_duration{kind:api}": ["p(95)<500", "p(99)<1000"],
  },
  summaryTrendStats: ["avg", "med", "p(95)", "p(99)", "max"],
};

const WORDS = Array.from({ length: 5000 }, (_, i) => `term${i}`);
function zipfQuery() {
  if (COLD) return `q${Math.floor(Math.random() * 1e9)}`;
  // rank ~ 1/u: about a third of searches hit the top 20 terms
  const r = Math.min(WORDS.length - 1, Math.floor(1 / Math.max(Math.random(), 0.0002)) - 1);
  return WORDS[r];
}
const api = { tags: { kind: "api" } };
const ok = (r) => check(r, { "status 200": (x) => x.status === 200 });

export default function () {
  if (__ITER === 0) {
    group("open page", () => { ok(http.get(`${BASE}/`, { tags: { kind: "page" } })); });
  }
  const q = zipfQuery();
  group("type", () => {
    ok(http.get(`${BASE}/api/web/suggest?q=${q.slice(0, 3)}`, api)); sleep(0.3);
    ok(http.get(`${BASE}/api/web/suggest?q=${q.slice(0, 5)}`, api)); sleep(0.3);
  });
  group("search", () => { ok(http.get(`${BASE}/api/web?q=${q}&type=web&page=1`, api)); });
  sleep(2 + Math.random() * 4);
  const roll = Math.random();
  if (roll < 0.3) {
    ok(http.get(`${BASE}/api/web?q=${q}&type=web&page=2`, api));
  } else if (roll < 0.55) {
    ok(http.get(`${BASE}/api/web?q=${q}&type=images&page=1`, api));
  } else if (roll < 0.7) {
    ok(http.get(`${BASE}/api/search?q=page+rank`, api));
  }
  sleep(5 + Math.random() * 10);
}
