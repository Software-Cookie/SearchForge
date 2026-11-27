"""Fake SearXNG + Wikipedia for load tests, so no real service is ever hammered.

    python loadtest/upstream_stub.py 9999

Answers /search (SearXNG JSON) and /w/api.php (Wikipedia opensearch) after 150-450 ms,
roughly what the real services take.
"""
import json
import random
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        url = urlparse(self.path)
        qs = parse_qs(url.query)
        time.sleep(random.uniform(0.15, 0.45))
        if url.path == "/search":
            q = qs.get("q", [""])[0]
            if qs.get("categories", [""])[0] == "images":
                body = {"results": [{"title": f"{q} {i}", "img_src": f"https://img.example/{i}.jpg",
                                     "thumbnail_src": f"https://img.example/t{i}.jpg",
                                     "url": f"https://site{i}.example/", "resolution": "800x600"}
                                    for i in range(20)]}
            else:
                body = {"results": [{"title": f"{q} result {i}", "url": f"https://site{i}.example/{i}",
                                     "content": f"All about {q}. " * 8} for i in range(10)]}
        elif url.path == "/w/api.php":
            q = qs.get("search", [""])[0]
            body = [q, [f"{q}{s}" for s in ("s", " history", " design", " price")]]
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer.request_queue_size = 1024
    ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1]) if len(sys.argv) > 1 else 9999),
                        Handler).serve_forever()
