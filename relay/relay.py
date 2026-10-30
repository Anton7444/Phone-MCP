#!/usr/bin/env python3
"""Phone MCP relay (reverse HTTP relay).

    Claude --HTTPS--> Caddy --> POST /mcp/<secret> --> relay --(long-poll)--> phone app --> local MCP server

The phone connects OUT to this relay, so it works behind NAT / mobile data and needs no tunnel app.

Phone API (the relay token is part of the path; it is NOT the MCP secret):
  POST /relay/<token>/poll          body {"h": sha256-hex(mcp secret), "v": 1}
       -> 200 {"id","method","path","headers","body_b64"}   a request for the phone to run locally
       -> 204                                               nothing arrived within POLL_SECONDS (poll again)
  POST /relay/<token>/reply/<id>    body {"status": int, "headers": {..}, "body_b64": ".."} -> 200

Public API: any method on /mcp/<secret>[/...]. The relay only knows sha256(secret) (sent by the phone), so it can
reject wrong paths itself (404) without waking the phone. Phone not polling -> 503, no answer in time -> 504.
The secret and the token are never logged.
"""
import base64
import collections
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("RELAY_TOKEN", "")
if len(TOKEN) < 32:
    sys.exit("RELAY_TOKEN is missing or shorter than 32 characters")
HOST = os.environ.get("RELAY_HOST", "127.0.0.1")
PORT = int(os.environ.get("RELAY_PORT", "18090"))
POLL_SECONDS = float(os.environ.get("POLL_SECONDS", "25"))
JOB_TIMEOUT = float(os.environ.get("JOB_TIMEOUT", "170"))      # how long a public caller waits for the phone
PHONE_ALIVE = float(os.environ.get("PHONE_ALIVE", str(POLL_SECONDS + 20)))
MAX_REQ_BODY = 2 * 1024 * 1024      # what Claude may send (the phone app itself accepts 1 MB)
MAX_REPLY_BODY = 16 * 1024 * 1024   # what the phone may answer (base64 JSON; screenshots can be large)
MAX_POLL_BODY = 4096
MAX_PENDING = 16

FWD_REQ_HEADERS = ("content-type", "accept", "mcp-protocol-version", "mcp-session-id")
FWD_RESP_HEADERS = ("content-type", "mcp-session-id", "cache-control", "allow")

cv = threading.Condition()
queue_: "collections.deque[dict]" = collections.deque()   # jobs waiting for a poll
waiting: dict = {}                                        # id -> job (queued or in flight)
phone = {"last": 0.0, "hash": None}


def safe_path(p: str) -> str:
    p = p.split("?")[0]
    seg = p.split("/")
    if len(seg) > 2 and seg[1] in ("mcp", "relay"):
        seg[2] = "***"
    return "/".join(seg[:5])


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 60
    server_version = "relay"
    sys_version = ""

    # ---- logging (never print secrets) ----
    def log_message(self, fmt, *args):
        pass

    def log_request(self, code="-", size="-"):
        print(f"{time.strftime('%H:%M:%S')} {self.command} {safe_path(self.path)} {code}", flush=True)

    # ---- helpers ----
    def _send(self, code, body=b"", ctype="text/plain; charset=utf-8", extra=None, close=False):
        self.send_response(code)
        if code != 204:
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        if close:
            self.send_header("Connection", "close")
            self.close_connection = True
        self.end_headers()
        if code != 204 and self.command != "HEAD":
            self.wfile.write(body)

    def text(self, code, msg, close=False):
        self._send(code, msg.encode(), close=close)

    def bare(self, code=404):
        self._send(code, b"Not Found" if code == 404 else b"", close=True)

    def read_body(self, limit=MAX_REQ_BODY) -> bytes:
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            data = bytearray()
            while True:
                line = self.rfile.readline(1024)
                if not line:
                    raise ValueError("eof")
                size = int(line.split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    while True:
                        t = self.rfile.readline(1024)
                        if t in (b"\r\n", b"\n", b""):
                            break
                    break
                if len(data) + size > limit:
                    raise OverflowError
                data += self.rfile.read(size)
                self.rfile.read(2)
            return bytes(data)
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise OverflowError
        return self.rfile.read(n) if n > 0 else b""

    def body_or_error(self, limit=MAX_REQ_BODY):
        try:
            return self.read_body(limit)
        except OverflowError:
            self.text(413, "too large", close=True)
        except Exception:
            self.text(400, "bad request", close=True)
        return None

    # ---- routing ----
    def route(self):
        p = self.path.split("?")[0]
        if p.startswith("/relay/"):
            seg = p.split("/")  # ['', 'relay', token, action, id?]
            if len(seg) < 4 or not hmac.compare_digest(seg[2].encode(), TOKEN.encode()):
                return self.bare()
            if self.command == "POST" and seg[3] == "poll" and len(seg) == 4:
                return self.handle_poll()
            if self.command == "POST" and seg[3] == "reply" and len(seg) == 5:
                return self.handle_reply(seg[4])
            return self.bare()
        if p.startswith("/mcp/"):
            return self.handle_public()
        return self.bare()

    do_GET = do_POST = do_PUT = do_DELETE = do_OPTIONS = route

    # ---- phone side ----
    def handle_poll(self):
        body = self.body_or_error(MAX_POLL_BODY)
        if body is None:
            return
        try:
            info = json.loads(body or b"{}")
            h = str(info.get("h", "")).lower()
            if len(h) != 64 or any(c not in "0123456789abcdef" for c in h):
                raise ValueError
        except Exception:
            return self.text(400, "bad poll body")
        job = None
        with cv:
            phone["hash"] = h
            phone["last"] = time.time()
            deadline = time.time() + POLL_SECONDS
            while True:
                if queue_:
                    job = queue_.popleft()
                    break
                left = deadline - time.time()
                if left <= 0:
                    break
                cv.wait(left)
            phone["last"] = time.time()
        if job is None:
            return self._send(204)
        payload = json.dumps({k: job[k] for k in ("id", "method", "path", "headers", "body_b64")}).encode()
        try:
            self._send(200, payload, ctype="application/json")
        except OSError:
            # the phone's poll died while delivering: give the job back (if the caller still waits)
            with cv:
                if job["id"] in waiting:
                    queue_.appendleft(job)
                    cv.notify()

    def handle_reply(self, jid):
        body = self.body_or_error(MAX_REPLY_BODY)
        if body is None:
            return
        try:
            info = json.loads(body)
            int(info["status"])
        except Exception:
            return self.text(400, "bad reply body")
        with cv:
            job = waiting.get(jid)
            phone["last"] = time.time()
        if job is None:
            return self.text(404, "gone")
        job["resp"] = info
        job["event"].set()
        self.text(200, "ok")

    # ---- public side (Claude) ----
    def handle_public(self):
        seg = self.path.split("?")[0].split("/")  # ['', 'mcp', secret, ...]
        secret = seg[2] if len(seg) > 2 else ""
        h = hashlib.sha256(secret.encode()).hexdigest()
        with cv:
            known = phone["hash"]
            alive = (time.time() - phone["last"]) < PHONE_ALIVE
            pending = len(waiting)
        if known is None or not hmac.compare_digest(h, known):
            return self.bare()
        body = self.body_or_error()
        if body is None:
            return
        if not alive:
            return self.text(503, "phone offline")
        if pending >= MAX_PENDING:
            return self.text(429, "busy")
        headers = {k: self.headers[k] for k in FWD_REQ_HEADERS if self.headers.get(k)}
        job = {
            "id": uuid.uuid4().hex,
            "method": self.command,
            "path": self.path,
            "headers": headers,
            "body_b64": base64.b64encode(body).decode(),
            "event": threading.Event(),
            "resp": None,
        }
        with cv:
            waiting[job["id"]] = job
            queue_.append(job)
            cv.notify()
        ok = job["event"].wait(JOB_TIMEOUT)
        with cv:
            waiting.pop(job["id"], None)
            try:
                queue_.remove(job)
            except ValueError:
                pass
        resp = job["resp"]
        if not ok or resp is None:
            return self.text(504, "phone did not answer in time")
        try:
            status = min(599, max(100, int(resp["status"])))
            out = base64.b64decode(resp.get("body_b64", "") or "")
        except Exception:
            return self.text(502, "bad answer from phone")
        extra, ctype = {}, "application/octet-stream"
        for k, v in (resp.get("headers") or {}).items():
            kl = str(k).lower()
            if kl == "content-type":
                ctype = str(v)
            elif kl in FWD_RESP_HEADERS and "\n" not in str(v) and "\r" not in str(v):
                extra[str(k)] = str(v)
        self._send(status, out, ctype=ctype, extra=extra)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64


if __name__ == "__main__":
    print(f"relay listening on {HOST}:{PORT}", flush=True)
    Server((HOST, PORT), Handler).serve_forever()
