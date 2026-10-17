"""
Minimal SMS server for the iSMS GW "persistent link" mode.

The phone connects here over WebSocket and keeps the connection open; the server sends
SMS commands and receives incoming messages and statuses in real time.

    pip install aiohttp
    python sms_server.py
    # In iSMS GW: Settings -> SMS server -> URL ws://<server-ip>:9000/device, token = DEVICE_TOKEN
    # Send an SMS: curl -X POST localhost:9000/send -d '{"to":"+15551234567","text":"Hello"}'

In production put the server behind an HTTPS reverse proxy (nginx / Caddy) and use wss://.
"""
import asyncio
import json
import secrets

from aiohttp import web, WSMsgType

DEVICE_TOKEN = "change-me-device-token"
devices: dict[str, web.WebSocketResponse] = {}


async def device_ws(request: web.Request):
    if request.headers.get("Authorization") != f"Bearer {DEVICE_TOKEN}":
        raise web.HTTPUnauthorized()
    device_id = request.headers.get("X-Device-Id", "unknown")
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    devices[device_id] = ws
    print(f"[+] device {device_id} ({request.headers.get('X-Device-Name')}) connected")
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            data = json.loads(msg.data)
            kind = data.get("type")
            if kind == "hello":
                print("    SIMs:", [(s["slot"], s["carrier"]) for s in data.get("sims", [])])
            elif kind == "event":
                m = data["data"]
                print(f"    {data['event']}: #{m['id']} {m['phone']}: {m['text'][:60]!r} [{m['status']}]")
                if data["event"] == "sms.received":
                    # Acknowledge, otherwise the phone re-sends the message after reconnecting.
                    await ws.send_json({"type": "ack_event", "messageId": m["id"]})
            elif kind in ("ack", "error", "status"):
                print("   ", data)
    finally:
        devices.pop(device_id, None)
        print(f"[-] device {device_id} disconnected")
    return ws


async def send(request: web.Request):
    body = await request.json()
    if not devices:
        raise web.HTTPServiceUnavailable(text="no device connected")
    ws = next(iter(devices.values()))
    cmd = {"type": "send", "id": body.get("id") or secrets.token_hex(4),
           "to": body["to"], "text": body["text"], "sim": body.get("sim", 0)}
    await ws.send_json(cmd)
    return web.json_response({"ok": True, "id": cmd["id"]})


app = web.Application()
app.add_routes([web.get("/device", device_ws), web.post("/send", send)])

if __name__ == "__main__":
    web.run_app(app, port=9000)
