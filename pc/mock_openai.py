# -*- coding: utf-8 -*-
"""端到端验证用的 mock OpenAI server：/v1/chat/completions 返回 SSE 流（带 usage），也支持非流式"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

REPLY = "好的，收到。这是 CloseRouter 端到端验证。"


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._json(200, {"object": "list", "data": [{"id": "mock-model", "object": "model"}]})
        else:
            self._json(404, {"error": "nf"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n) if n else b"{}"
        try:
            req = json.loads(body)
        except Exception:
            req = {}
        in_tokens = max(len(body) // 4, 1)
        out_text = REPLY
        out_tokens = len(REPLY)  # mock: 1 token/字
        if req.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for ch in out_text:
                chunk = {"id": "c1", "object": "chat.completion.chunk",
                         "choices": [{"index": 0, "delta": {"content": ch}, "finish_reason": None}]}
                self.wfile.write(("data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n").encode("utf-8"))
                self.wfile.flush()
            final = {"id": "c1", "object": "chat.completion.chunk", "choices": [],
                     "usage": {"prompt_tokens": in_tokens, "completion_tokens": out_tokens}}
            self.wfile.write(("data: " + json.dumps(final) + "\n\n").encode("utf-8"))
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        else:
            self._json(200, {
                "id": "c1", "object": "chat.completion",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": out_text}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": in_tokens, "completion_tokens": out_tokens},
            })

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9999
    print(f"mock openai server on 127.0.0.1:{port}", flush=True)
    HTTPServer(("127.0.0.1", port), H).serve_forever()
