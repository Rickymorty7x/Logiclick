#!/usr/bin/env python3
"""Small authenticated command queue for the Logiclick Android client."""

from __future__ import annotations

import base64
import hmac
import json
import mimetypes
import os
import re
import secrets
import sqlite3
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


DATA_DIR = Path(os.environ.get("LOGICLICK_DATA_DIR", Path(__file__).parent / "data"))
DB_PATH = DATA_DIR / "logiclick.sqlite3"
KEY_PATH = DATA_DIR / "ed25519-private.pem"
PACKAGED_CONTROLLER_DIR = Path(__file__).parent / "controller"
REPOSITORY_CONTROLLER_DIR = Path(__file__).parent.parent / "controller-web" / "dist"
CONTROLLER_DIR = Path(os.environ["LOGICLICK_CONTROLLER_DIR"]) if os.environ.get("LOGICLICK_CONTROLLER_DIR") else (
    PACKAGED_CONTROLLER_DIR if PACKAGED_CONTROLLER_DIR.is_dir() else REPOSITORY_CONTROLLER_DIR
)
DEVICE_ID = os.environ.get("LOGICLICK_DEVICE_ID", "phone-1")
DEVICE_TOKEN = os.environ.get("LOGICLICK_DEVICE_TOKEN", "")
ADMIN_TOKEN = os.environ.get("LOGICLICK_ADMIN_TOKEN", "")
HOST = os.environ.get("LOGICLICK_HOST", "127.0.0.1")
PORT = int(os.environ.get("LOGICLICK_PORT", "8765"))
ALLOWED_ORIGIN = os.environ.get("LOGICLICK_ALLOWED_ORIGIN", "*")
INTELLIGENCE_URL = os.environ.get("LOGICLICK_INTELLIGENCE_URL", "")
INTELLIGENCE_TOKEN = os.environ.get("LOGICLICK_INTELLIGENCE_TOKEN", "")
TRUST_LOCAL_CONTROLLER = os.environ.get("LOGICLICK_TRUST_LOCAL_CONTROLLER", "0").lower() in {"1", "true", "yes"}
ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
PACKAGE_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_.]{2,200}$")
PROTECTED_PACKAGES = (
    "android",
    "com.android.settings",
    "com.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.google.android.permissioncontroller",
)


def require_configuration() -> None:
    if not ID_PATTERN.fullmatch(DEVICE_ID):
        raise SystemExit("LOGICLICK_DEVICE_ID is invalid")
    if len(DEVICE_TOKEN) < 24 or len(ADMIN_TOKEN) < 24:
        raise SystemExit("LOGICLICK_DEVICE_TOKEN and LOGICLICK_ADMIN_TOKEN must each be at least 24 characters")
    if hmac.compare_digest(DEVICE_TOKEN, ADMIN_TOKEN):
        raise SystemExit("Device and admin tokens must be different")


def database() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH, timeout=5)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute(
        """CREATE TABLE IF NOT EXISTS commands (
            command_id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            envelope TEXT NOT NULL,
            state TEXT NOT NULL,
            created_ms INTEGER NOT NULL,
            expires_ms INTEGER NOT NULL,
            delivered_ms INTEGER
        )"""
    )
    connection.commit()
    return connection


def load_private_key() -> Ed25519PrivateKey:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if KEY_PATH.exists():
        return serialization.load_pem_private_key(KEY_PATH.read_bytes(), password=None)
    key = Ed25519PrivateKey.generate()
    KEY_PATH.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    KEY_PATH.chmod(0o600)
    return key


def public_key_base64(key: Ed25519PrivateKey) -> str:
    encoded = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(encoded).decode("ascii")


def canonical_payload(action: dict) -> str:
    return base64.b64encode(
        json.dumps(action, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    ).decode("ascii")


def make_envelope(action: dict, ttl_seconds: int, key: Ed25519PrivateKey) -> dict:
    now = int(time.time() * 1000)
    command_id = f"cmd_{uuid.uuid4().hex}"
    nonce = secrets.token_urlsafe(18)
    payload = canonical_payload(action)
    expires = now + ttl_seconds * 1000
    signing = "\n".join((command_id, str(now), str(expires), nonce, payload)).encode("utf-8")
    return {
        "commandId": command_id,
        "issuedAtEpochMs": now,
        "expiresAtEpochMs": expires,
        "nonce": nonce,
        "payload": payload,
        "signature": base64.b64encode(key.sign(signing)).decode("ascii"),
    }


def validate_area(value: object, *, normalized: bool) -> None:
    if not isinstance(value, dict) or set(value) != {"left", "top", "right", "bottom"}:
        raise ValueError("Each area must contain exactly left, top, right and bottom")
    numbers = [value[name] for name in ("left", "top", "right", "bottom")]
    if any(isinstance(item, bool) or not isinstance(item, (int, float)) for item in numbers):
        raise ValueError("Area coordinates must be numbers")
    left, top, right, bottom = map(float, numbers)
    if not (left <= right and top <= bottom and left >= 0 and top >= 0):
        raise ValueError("Area bounds are invalid")
    if normalized and (right > 1 or bottom > 1):
        raise ValueError("Normalized coordinates must be between 0 and 1")
    if not normalized and (right > 20_000 or bottom > 20_000):
        raise ValueError("Pixel coordinates are too large")


def validate_timing(value: object, name: str) -> None:
    if value is None:
        return
    if not isinstance(value, dict) or set(value) != {"min", "max"}:
        raise ValueError(f"{name} must contain min and max")
    minimum, maximum = value["min"], value["max"]
    if any(isinstance(item, bool) or not isinstance(item, int) for item in (minimum, maximum)):
        raise ValueError(f"{name} values must be integer milliseconds")
    if not 1 <= minimum <= maximum <= 60_000:
        raise ValueError(f"{name} must be within 1–60000 ms")


def validate_action(action: object) -> dict:
    if not isinstance(action, dict):
        raise ValueError("JSON body must be an object")
    action_type = action.get("type")
    if action_type not in {
        "tap", "double_tap", "long_press", "swipe", "drag", "scroll", "fling",
        "gesture", "global_action", "launch_app"
    }:
        raise ValueError("Unsupported action type")
    if action_type == "global_action":
        if action.get("action") not in {
            "back", "home", "recents", "notifications", "quick_settings", "dismiss_notification_shade"
        }:
            raise ValueError("Unsupported global action")
        return action
    if action_type == "launch_app":
        package = action.get("packageName", "")
        if not PACKAGE_PATTERN.fullmatch(package) or any(
            package == blocked or package.startswith(f"{blocked}.") for blocked in PROTECTED_PACKAGES
        ):
            raise ValueError("Invalid or protected package name")
        return action

    space = action.get("coordinateSpace", "normalized")
    if space not in {"normalized", "pixels"}:
        raise ValueError("coordinateSpace must be normalized or pixels")
    normalized = space == "normalized"
    if action_type in {"tap", "double_tap", "long_press"}:
        validate_area(action.get("area"), normalized=normalized)
        validate_timing(action.get("holdMs"), "holdMs")
        if action_type == "double_tap":
            validate_timing(action.get("gapMs"), "gapMs")
    elif action_type in {"swipe", "drag", "scroll", "fling"}:
        validate_area(action.get("startArea"), normalized=normalized)
        validate_area(action.get("endArea"), normalized=normalized)
        validate_timing(action.get("durationMs"), "durationMs")
    else:
        strokes = action.get("strokes")
        if not isinstance(strokes, list) or not 1 <= len(strokes) <= 10:
            raise ValueError("gesture.strokes must contain 1–10 strokes")
        for stroke in strokes:
            if not isinstance(stroke, dict):
                raise ValueError("Each stroke must be an object")
            points = stroke.get("points")
            if not isinstance(points, list) or not 1 <= len(points) <= 64:
                raise ValueError("Each stroke needs 1–64 point areas")
            for point in points:
                validate_area(point, normalized=normalized)
            delay = stroke.get("startDelayMs", 0)
            if isinstance(delay, bool) or not isinstance(delay, int) or not 0 <= delay <= 60_000:
                raise ValueError("startDelayMs must be within 0–60000 ms")
            validate_timing(stroke.get("durationMs"), "durationMs")
    return action


class LogiclickHandler(BaseHTTPRequestHandler):
    server_version = "Logiclick/0.4"

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.log_date_time_string()} {self.client_address[0]} {fmt % args}", flush=True)

    def end_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", ALLOWED_ORIGIN)
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Vary", "Origin")
        super().end_headers()

    def send_json(self, status: int, value: object) -> None:
        body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, status: int, value: bytes, content_type: str, captured_at: int | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(value)))
        self.send_header("Cache-Control", "no-store")
        if captured_at is not None:
            self.send_header("X-Captured-At", str(captured_at))
        self.end_headers()
        self.wfile.write(value)

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(HTTPStatus.NO_CONTENT)
        self.end_headers()

    def bearer_matches(self, expected: str) -> bool:
        supplied = self.headers.get("Authorization", "")
        return supplied.startswith("Bearer ") and hmac.compare_digest(supplied[7:], expected)

    def admin_matches(self) -> bool:
        return self.bearer_matches(ADMIN_TOKEN) or (
            TRUST_LOCAL_CONTROLLER and self.bearer_matches(self.server.controller_session_token)
        )

    def is_same_origin_controller(self) -> bool:
        if not TRUST_LOCAL_CONTROLLER:
            return False
        host = self.headers.get("Host", "")
        if not host:
            return False
        allowed_origins = {f"http://{host}", f"https://{host}"}
        origin = self.headers.get("Origin", "")
        if origin:
            return origin in allowed_origins
        referer = self.headers.get("Referer", "")
        return any(referer.startswith(f"{allowed}/controller/") for allowed in allowed_origins)

    def read_json(self) -> object:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ValueError("Invalid Content-Length") from error
        if not 1 <= length <= 32_768:
            raise ValueError("Request body must be 1–32768 bytes")
        return json.loads(self.rfile.read(length))

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path in {"/", "/controller"}:
            self.send_response(HTTPStatus.FOUND)
            self.send_header("Location", "/controller/")
            self.end_headers()
            return
        if path == "/controller/" or path.startswith("/controller/"):
            name = "index.html" if path == "/controller/" else path.removeprefix("/controller/")
            if "/" in name or name.startswith("."):
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
                return
            target = CONTROLLER_DIR / name
            if not target.is_file():
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
                return
            self.send_bytes(HTTPStatus.OK, target.read_bytes(), mimetypes.guess_type(target.name)[0] or "application/octet-stream")
            return
        if path == "/v1/health":
            self.send_json(HTTPStatus.OK, {"ok": True, "service": "logiclick", "version": "0.4"})
            return
        if path == "/v1/controller/session":
            if not self.is_same_origin_controller():
                self.send_json(HTTPStatus.FORBIDDEN, {"error": "Open the controller from this Pi first"})
                return
            self.send_json(HTTPStatus.OK, {"token": self.server.controller_session_token})
            return
        if path == "/v1/public-key":
            self.send_json(HTTPStatus.OK, {"algorithm": "Ed25519", "publicKeyBase64": self.server.public_key})
            return
        match = re.fullmatch(r"/v1/devices/([^/]+)/commands/next", path)
        if match:
            if not self.bearer_matches(DEVICE_TOKEN):
                self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
                return
            device = unquote(match.group(1))
            if device != DEVICE_ID:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Unknown device"})
                return
            envelope = self.server.next_command(device)
            if envelope is None:
                self.send_response(HTTPStatus.NO_CONTENT)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
            else:
                self.send_json(HTTPStatus.OK, envelope)
            return
        match = re.fullmatch(r"/v1/devices/([^/]+)/mirror/(latest|status)", path)
        if match:
            if not self.admin_matches():
                self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
                return
            device = unquote(match.group(1))
            if device != DEVICE_ID:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Unknown device"})
                return
            frame = DATA_DIR / "mirror" / f"{device}.jpg"
            if not frame.exists():
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "No mirror frame received"})
                return
            captured_at = int(frame.stat().st_mtime * 1000)
            if match.group(2) == "status":
                self.send_json(HTTPStatus.OK, {"available": True, "capturedAtEpochMs": captured_at, "bytes": frame.stat().st_size})
            else:
                self.send_bytes(HTTPStatus.OK, frame.read_bytes(), "image/jpeg", captured_at)
            return
        self.send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        mirror_match = re.fullmatch(r"/v1/devices/([^/]+)/mirror/frame", path)
        if mirror_match:
            if not self.bearer_matches(DEVICE_TOKEN):
                self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
                return
            device = unquote(mirror_match.group(1))
            if device != DEVICE_ID:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Unknown device"})
                return
            if self.headers.get("Content-Type", "").split(";", 1)[0].strip() != "image/jpeg":
                self.send_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "Expected image/jpeg"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            if not 100 <= length <= 2_500_000:
                self.send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "Frame must be 100–2500000 bytes"})
                return
            directory = DATA_DIR / "mirror"
            directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            target = directory / f"{device}.jpg"
            temporary = directory / f".{device}.{secrets.token_hex(6)}.tmp"
            temporary.write_bytes(self.rfile.read(length))
            temporary.replace(target)
            self.send_json(HTTPStatus.OK, {"stored": True, "bytes": length})
            return

        intelligence_match = re.fullmatch(r"/v1/devices/([^/]+)/intelligence/propose", path)
        if intelligence_match:
            if not self.admin_matches():
                self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
                return
            device = unquote(intelligence_match.group(1))
            if device != DEVICE_ID:
                self.send_json(HTTPStatus.NOT_FOUND, {"error": "Unknown device"})
                return
            if not INTELLIGENCE_URL:
                self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Intelligence endpoint is not configured"})
                return
            frame = DATA_DIR / "mirror" / f"{device}.jpg"
            if not frame.exists():
                self.send_json(HTTPStatus.CONFLICT, {"error": "No mirror frame is available"})
                return
            try:
                request_body = self.read_json()
                goal = request_body.get("goal", "") if isinstance(request_body, dict) else ""
                if not isinstance(goal, str) or not 1 <= len(goal) <= 2_000:
                    raise ValueError("goal must contain 1–2000 characters")
                upstream_body = json.dumps({
                    "goal": goal,
                    "deviceId": device,
                    "frame": {
                        "mimeType": "image/jpeg",
                        "dataBase64": base64.b64encode(frame.read_bytes()).decode("ascii"),
                        "capturedAtEpochMs": int(frame.stat().st_mtime * 1000),
                    },
                    "responseContract": {
                        "rationale": "short string",
                        "confidence": "number from 0 to 1",
                        "action": "one Logiclick gesture action object",
                    },
                }, separators=(",", ":")).encode()
                headers = {"Content-Type": "application/json", "Accept": "application/json"}
                if INTELLIGENCE_TOKEN:
                    headers["Authorization"] = f"Bearer {INTELLIGENCE_TOKEN}"
                upstream = Request(INTELLIGENCE_URL, data=upstream_body, headers=headers, method="POST")
                with urlopen(upstream, timeout=30) as response:
                    proposal = json.load(response)
                if not isinstance(proposal, dict):
                    raise ValueError("Intelligence response must be an object")
                proposal["action"] = validate_action(proposal.get("action"))
                confidence = proposal.get("confidence", 0)
                if isinstance(confidence, bool) or not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
                    raise ValueError("confidence must be between 0 and 1")
            except (ValueError, json.JSONDecodeError) as error:
                self.send_json(HTTPStatus.BAD_GATEWAY, {"error": str(error)})
                return
            except Exception as error:
                self.send_json(HTTPStatus.BAD_GATEWAY, {"error": f"Intelligence endpoint failed: {error}"})
                return
            self.send_json(HTTPStatus.OK, proposal)
            return

        match = re.fullmatch(r"/v1/devices/([^/]+)/commands", path)
        if not match:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if not self.admin_matches():
            self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Unauthorized"})
            return
        device = unquote(match.group(1))
        if device != DEVICE_ID:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Unknown device"})
            return
        try:
            request = self.read_json()
            if not isinstance(request, dict):
                raise ValueError("JSON body must be an object")
            ttl = request.pop("ttlSeconds", 60)
            if isinstance(ttl, bool) or not isinstance(ttl, int) or not 1 <= ttl <= 120:
                raise ValueError("ttlSeconds must be an integer from 1 to 120")
            action = validate_action(request)
            envelope = make_envelope(action, ttl, self.server.private_key)
            position = self.server.enqueue(device, envelope)
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
            return
        self.send_json(
            HTTPStatus.CREATED,
            {"commandId": envelope["commandId"], "expiresAtEpochMs": envelope["expiresAtEpochMs"], "queuePosition": position},
        )


class LogiclickServer(ThreadingHTTPServer):
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int]):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        self.private_key = load_private_key()
        self.public_key = public_key_base64(self.private_key)
        self.controller_session_token = secrets.token_urlsafe(32)
        with database():
            pass
        super().__init__(address, LogiclickHandler)

    def enqueue(self, device: str, envelope: dict) -> int:
        now = int(time.time() * 1000)
        with database() as connection:
            connection.execute("DELETE FROM commands WHERE expires_ms <= ?", (now,))
            count = connection.execute(
                "SELECT COUNT(*) FROM commands WHERE device_id = ? AND state = 'queued'", (device,)
            ).fetchone()[0]
            if count >= 100:
                raise ValueError("Queue is full")
            connection.execute(
                "INSERT INTO commands VALUES (?, ?, ?, 'queued', ?, ?, NULL)",
                (
                    envelope["commandId"],
                    device,
                    json.dumps(envelope, separators=(",", ":")),
                    now,
                    envelope["expiresAtEpochMs"],
                ),
            )
            return count + 1

    def next_command(self, device: str) -> dict | None:
        now = int(time.time() * 1000)
        with database() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM commands WHERE expires_ms <= ?", (now,))
            row = connection.execute(
                "SELECT command_id, envelope FROM commands WHERE device_id = ? AND state = 'queued' ORDER BY created_ms LIMIT 1",
                (device,),
            ).fetchone()
            if row is None:
                return None
            connection.execute(
                "UPDATE commands SET state = 'delivered', delivered_ms = ? WHERE command_id = ?", (now, row[0])
            )
            return json.loads(row[1])


def main() -> None:
    require_configuration()
    server = LogiclickServer((HOST, PORT))
    print(f"Logiclick API listening on http://{HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
