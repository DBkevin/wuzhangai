import argparse
import json
import sys
import threading
import uuid
from collections import deque
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Windows 控制台默认 GBK，打印中文/表情会抛 UnicodeEncodeError 并中断响应。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def decide(payload):
    title = payload.get("title")
    rid = str(uuid.uuid4())

    # 个人资料页：返回识别到的微信号，并提示已与来源会话绑定
    if payload.get("page_type") == "profile":
        wxid = payload.get("contact_wechat_id")
        linked = payload.get("linked_title")
        if wxid and linked:
            return {
                "action": "show_hint",
                "hint": "已绑定：" + str(linked) + " = " + str(wxid),
                "candidates": [],
                "request_id": rid,
            }
        if wxid:
            return {
                "action": "show_hint",
                "hint": "PROFILE-ID " + str(wxid),
                "candidates": [],
                "request_id": rid,
            }
        return {
            "action": "show_hint",
            "hint": "PROFILE-NO-ID (对方未公开微信号或读取失败)",
            "candidates": [],
            "request_id": rid,
        }

    if not title:
        return {"action": "no_match", "hint": "", "candidates": [], "request_id": rid}

    # 长文本测试：用于验证浮窗的滚动与点按复制
    if "王强" in title:
        long_hint = (
            "【长文本测试】该顾客在客服A下已存在，备注：王强-8321-川田来中心-26121466889。\n"
            "历史订单：2026-03-12 初诊（脱毛），2026-05-08 复诊（水光），2026-07-19 复诊（光子嫩肤）。\n"
            "关联客服：客服A（2026-03-12 起）、客服B（2026-06-01 至 2026-08-30）。\n"
            "注意：该顾客曾反馈对麻药过敏，术前需再次确认；上次到店未完成第 3 次疗程。\n"
            "建议动作：先核对手机号尾号 6889，确认后在中控后台把任务改派给客服A。\n"
            "—— 以上为滚动测试文本，请尝试上下滑动查看，并点按任意位置复制。"
        )
        return {
            "action": "show_hint",
            "hint": long_hint,
            "candidates": [],
            "request_id": rid,
        }
    if "A" in title:
        return {
            "action": "show_candidates",
            "hint": "",
            "request_id": rid,
            "candidates": [
                {"label": "KF-A same remark", "confidence": 0.92, "evidence": "phone 138****1234"},
                {"label": "KF-B suspected", "confidence": 0.61, "evidence": "same address in 30d"},
                {"label": "KF-C history", "confidence": 0.38, "evidence": "nickname only"},
            ],
        }
    return {"action": "show_hint", "hint": "LINK-OK " + title, "candidates": [], "request_id": rid}


class Store:
    def __init__(self):
        self.lock = threading.Lock()
        self.events = deque(maxlen=100)
        self.feedback = deque(maxlen=100)

    def snapshot(self):
        with self.lock:
            return {"events": list(self.events), "feedback": list(self.feedback)}

    def add_report(self, payload, resp):
        with self.lock:
            self.events.appendleft({
                "at": datetime.now().strftime("%H:%M:%S"),
                "action": resp.get("action"),
                "title": payload.get("title"),
                "msgs": payload.get("recent_messages"),
                "read_status": payload.get("read_status"),
                "account": payload.get("service_account_id"),
            })

    def add_feedback(self, body):
        with self.lock:
            self.feedback.appendleft({"at": datetime.now().strftime("%H:%M:%S"), "body": body})


STORE = Store()


PANEL = """<!doctype html>
<html lang=zh><head><meta charset=utf-8><title>kf center stub</title>
<style>
body{font:14px/1.6 Segoe UI,Microsoft YaHei,sans-serif;margin:0;padding:20px;background:#f5f6f8;color:#222}
h1{font-size:18px;margin:0 0 4px}.sub{color:#666;font-size:12px;margin-bottom:14px}
.card{background:#fff;border-radius:10px;padding:12px 14px;margin-bottom:12px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
.row{border-top:1px solid #eee;padding:8px 0}.row:first-child{border-top:0}
.act{background:#eef;color:#33c;padding:1px 7px;border-radius:8px;font-size:12px}
.msg{color:#555;font-size:12px}.empty{color:#999}
</style></head><body>
<h1>KF Center Stub</h1>
<div class=sub>auto refresh 2s &middot; reports: {count}</div>
<div class=card><b>recent reports</b>{events}</div>
<div class=card><b>feedback</b>{feedback}</div>
<meta http-equiv=refresh content=2>
</body></html>"""


def render_panel():
    snap = STORE.snapshot()
    rows = []
    for e in snap["events"]:
        action = e["action"] or "?"
        msgs = " | ".join(e["msgs"] or []) or "(empty)"
        title = e["title"]
        if title is None:
            title = "(null)"
        rows.append(
            "<div class=row><span class=act>" + str(action) + "</span> "
            + "title=" + str(title) + " &middot; read_status=" + str(e["read_status"])
            + " &middot; " + str(e["at"]) + " &middot; id=" + str(e["account"])
            + "<br><span class=msg>" + msgs + "</span></div>"
        )
    events_html = "".join(rows) or "<div class=empty>no report yet</div>"
    fb = []
    for f in snap["feedback"]:
        fb.append("<div class=row>" + str(f["at"]) + " " + json.dumps(f["body"], ensure_ascii=False) + "</div>")
    feedback_html = "".join(fb) or "<div class=empty>no feedback yet</div>"
    return PANEL.replace("{count}", str(snap["count"])).replace("{events}", events_html).replace("{feedback}", feedback_html).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    server_version = "KfCenterStub/1.0"

    def log_message(self, fmt, *args):
        pass

    def _read_json(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        raw = self.rfile.read(n) if n else b""
        if not raw:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return None

    def _send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def do_GET(self):
        if self.path.rstrip("/") in ("", "/health"):
            self._send(200, b"{\"ok\":true}", "application/json; charset=utf-8")
        else:
            self._send(200, render_panel(), "text/html; charset=utf-8")

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
        body = self._read_json()
        if path in ("/ingest", ""):
            if not isinstance(body, dict):
                self._send(400, b"{\"error\":\"bad json\"}", "application/json; charset=utf-8")
                return
            resp = decide(body)
            STORE.add_report(body, resp)
            print("  [ingest] action=" + str(resp.get("action")) + " read_status=" + str(body.get("read_status")), flush=True)
            print("  [ingest] title=" + json.dumps(body.get("title"), ensure_ascii=False), flush=True)
            print("  [ingest] msgs=" + json.dumps(body.get("recent_messages"), ensure_ascii=False), flush=True)
            out = json.dumps(resp, ensure_ascii=False).encode("utf-8")
            self._send(200, out, "application/json; charset=utf-8")
            return
        if path == "/feedback":
            if isinstance(body, dict):
                STORE.add_feedback(body)
                print("  [feedback] " + json.dumps(body, ensure_ascii=False), flush=True)
            self._send(200, b"{\"ok\":true}", "application/json; charset=utf-8")
            return
        self._send(404, b"{\"error\":\"not found\"}", "application/json; charset=utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8099)
    args = ap.parse_args()
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print("center stub listening on http://" + args.host + ":" + str(args.port), flush=True)
    print("  POST /ingest    report", flush=True)
    print("  POST /feedback  feedback", flush=True)
    print("  GET  /          panel", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("stopped")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
