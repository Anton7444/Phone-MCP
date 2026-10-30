import base64, hashlib, http.client, json, os, socket, subprocess, sys, threading, time

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 18190
TOKEN = "t" * 43
SECRET = "mysecret_abc"
env = dict(os.environ, RELAY_TOKEN=TOKEN, RELAY_PORT=str(PORT), POLL_SECONDS="2", PHONE_ALIVE="3", JOB_TIMEOUT="4")
proc = subprocess.Popen([sys.executable, os.path.join(HERE, "relay.py")], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(0.8)

fails = []
def check(name, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + name + (" " + str(extra) if not cond else ""))
    if not cond: fails.append(name)

def req(method, path, body=None, headers=None, timeout=15):
    c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=timeout)
    c.request(method, path, body=body, headers=headers or {})
    r = c.getresponse(); data = r.read(); c.close()
    return r.status, dict((k.lower(), v) for k, v in r.getheaders()), data

stop_phone = threading.Event()
seen = []
def phone(delay=0.0, stop=stop_phone):
    h = hashlib.sha256(SECRET.encode()).hexdigest()
    while not stop.is_set():
        try:
            st, _, data = req("POST", f"/relay/{TOKEN}/poll", json.dumps({"h": h, "v": 1}), timeout=10)
        except Exception:
            time.sleep(0.2); continue
        if st != 200: continue
        job = json.loads(data); seen.append(job)
        def answer(job=job):
            time.sleep(delay)
            body = base64.b64decode(job["body_b64"])
            out = json.dumps({"echo": job["path"], "method": job["method"], "len": len(body), "hdr": job["headers"]}).encode()
            reply = {"status": 201 if job["method"] == "POST" else 405, "headers": {"Content-Type": "application/json", "X-Evil": "no"}, "body_b64": base64.b64encode(out).decode()}
            req("POST", f"/relay/{TOKEN}/reply/{job['id']}", json.dumps(reply))
        threading.Thread(target=answer, daemon=True).start()

try:
    # before any phone connects: unknown secret -> 404
    st, _, _ = req("POST", f"/mcp/{SECRET}", "{}")
    check("404 before phone ever registered", st == 404, st)

    t = threading.Thread(target=phone, daemon=True); t.start(); time.sleep(0.5)

    st, hd, data = req("POST", f"/mcp/{SECRET}", '{"jsonrpc":"2.0"}', {"Content-Type": "application/json", "Accept": "application/json", "X-Other": "drop"})
    j = json.loads(data)
    check("roundtrip status/body", st == 201 and j["echo"] == f"/mcp/{SECRET}" and j["len"] == 17, (st, data))
    check("content-type forwarded", hd.get("content-type") == "application/json", hd)
    check("unlisted resp header dropped", "x-evil" not in hd, hd)
    check("unlisted req header dropped", "x-other" not in j["hdr"] and j["hdr"].get("content-type") == "application/json", j)

    st, _, data = req("GET", f"/mcp/{SECRET}")
    check("GET forwarded (phone answers 405)", st == 405, st)

    st, _, _ = req("POST", "/mcp/wrongsecret", "{}")
    check("wrong secret 404 without waking phone", st == 404 and len(seen) == 2, (st, len(seen)))
    st, _, _ = req("POST", "/relay/badtoken/poll", "{}")
    check("bad relay token 404", st == 404, st)
    st, _, _ = req("GET", "/")
    check("root 404", st == 404, st)

    # chunked request body
    s = socket.create_connection(("127.0.0.1", PORT)); body = b"hello world!"
    s.sendall((f"POST /mcp/{SECRET} HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n").encode()
              + b"5\r\nhello\r\n7\r\n world!\r\n0\r\n\r\n")
    resp = b""
    while b"\r\n\r\n" not in resp or b"}" not in resp.split(b"\r\n\r\n", 1)[1]:
        chunk = s.recv(4096)
        if not chunk: break
        resp += chunk
    s.close()
    check("chunked body decoded", b'"len": 12' in resp, resp[:200])

    # concurrency: 6 parallel calls, phone answers each after 0.5s
    stop_phone.set(); time.sleep(2.3)   # let old poll loop end
    stop2 = threading.Event()
    for _ in range(3): threading.Thread(target=phone, args=(0.5, stop2), daemon=True).start()
    time.sleep(0.4)
    results = []
    def call(i): results.append(req("POST", f"/mcp/{SECRET}", "x" * i)[0])
    ts = [threading.Thread(target=call, args=(i,)) for i in range(6)]
    t0 = time.time(); [x.start() for x in ts]; [x.join() for x in ts]
    check("6 parallel calls ok", results.count(201) == 6, results)
    check("handled in parallel (<2.5s)", time.time() - t0 < 2.5, time.time() - t0)

    # phone goes offline -> 503 with the right secret, 404 with a wrong one
    stop2.set(); time.sleep(6)
    st, _, _ = req("POST", f"/mcp/{SECRET}", "{}")
    check("offline -> 503", st == 503, st)
    st, _, _ = req("POST", "/mcp/nope", "{}")
    check("offline + wrong secret -> 404", st == 404, st)

    # phone polls but never answers -> 504
    h = hashlib.sha256(SECRET.encode()).hexdigest()
    def silent():
        req("POST", f"/relay/{TOKEN}/poll", json.dumps({"h": h}), timeout=10)
    threading.Thread(target=silent, daemon=True).start(); time.sleep(0.3)
    t0 = time.time(); st, _, _ = req("POST", f"/mcp/{SECRET}", "{}")
    check("no answer -> 504 after JOB_TIMEOUT", st == 504 and 3.5 < time.time() - t0 < 6, (st, time.time() - t0))

    # late reply for a finished job -> 404 gone
    st, _, _ = req("POST", f"/relay/{TOKEN}/reply/deadbeef", json.dumps({"status": 200}))
    check("reply to unknown id -> 404", st == 404, st)
finally:
    proc.terminate()
    out = proc.communicate(timeout=5)[0]
    leaked = SECRET in out or TOKEN in out
    check("secret/token never logged", not leaked)
    print("---- relay log (tail) ----"); print("\n".join(out.splitlines()[-6:]))
print("FAILED: " + ", ".join(fails) if fails else "ALL PASSED")
sys.exit(1 if fails else 0)
