#!/usr/bin/env python3
"""
LR control plane — reference implementation.

Implements the three endpoints the LR Android client calls. Audio bytes never pass through this
process: it hands the client a short-lived presigned URL and the phone PUTs straight to object
storage.

    POST /api/v1/enroll    {clientId, deviceName}   [Authorization: Bearer <enrollment token>]
      -> {deviceToken, format:{...}, retention:{...}}
    GET  /api/v1/config    Authorization: Bearer <deviceToken>
      -> {format:{...}, retention:{...}}
    POST /api/v1/presign   Authorization: Bearer <deviceToken>
                           {filename, contentType, sizeBytes}
      -> {uploadUrl, expiresAt}

This is a reference, not hardened production code. It is deliberately small enough to read in
one sitting. Before exposing it publicly you want, at minimum: rate limiting, structured logging,
and a real datastore instead of a JSON file.
"""

import json
import os
import secrets
import time
import ipaddress
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import boto3
from botocore.config import Config as BotoConfig

# --- configuration (environment) -------------------------------------------------------------

LISTEN_PORT = int(os.environ.get("LR_PORT", "8080"))
STATE_FILE = os.environ.get("LR_STATE_FILE", "/data/devices.json")

# Enrollment tokens that may be embedded in a QR code. Comma-separated.
ENROLLMENT_TOKENS = {t for t in os.environ.get("LR_ENROLLMENT_TOKENS", "").split(",") if t}

# Networks allowed to enroll WITHOUT a token, for the client's well-known probe. Comma-separated
# CIDRs, e.g. "10.0.0.0/8,192.168.0.0/16". Empty means well-known enrollment is disabled and every
# device must scan a QR code.
TRUSTED_NETWORKS = [
    ipaddress.ip_network(c.strip())
    for c in os.environ.get("LR_TRUSTED_NETWORKS", "").split(",")
    if c.strip()
]

# Recording settings pushed to every device.
FORMAT = {
    "codec": os.environ.get("LR_CODEC", "aac"),          # "aac" (.m4a) or "opus" (.ogg)
    "bitrate": int(os.environ.get("LR_BITRATE", "96000")),
    "sampleRate": int(os.environ.get("LR_SAMPLE_RATE", "44100")),
}
RETENTION = {
    "daysUntilTrash": int(os.environ.get("LR_DAYS_UNTIL_TRASH", "15")),
    "daysUntilPurge": int(os.environ.get("LR_DAYS_UNTIL_PURGE", "30")),
}

S3_ENDPOINT = os.environ.get("LR_S3_ENDPOINT", "http://minio:9000")
S3_PUBLIC_ENDPOINT = os.environ.get("LR_S3_PUBLIC_ENDPOINT", S3_ENDPOINT)
S3_BUCKET = os.environ.get("LR_S3_BUCKET", "recordings")
S3_KEY = os.environ.get("LR_S3_ACCESS_KEY", "minioadmin")
S3_SECRET = os.environ.get("LR_S3_SECRET_KEY", "minioadmin")
PRESIGN_TTL_SECONDS = int(os.environ.get("LR_PRESIGN_TTL", "900"))

MAX_UPLOAD_BYTES = int(os.environ.get("LR_MAX_UPLOAD_BYTES", str(512 * 1024 * 1024)))

s3 = boto3.client(
    "s3",
    endpoint_url=S3_PUBLIC_ENDPOINT,
    aws_access_key_id=S3_KEY,
    aws_secret_access_key=S3_SECRET,
    config=BotoConfig(signature_version="s3v4"),
)


# --- device store ------------------------------------------------------------------------------

def load_devices():
    try:
        with open(STATE_FILE) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_devices(devices):
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(devices, f, indent=2)
    os.replace(tmp, STATE_FILE)


DEVICES = load_devices()


def device_for_token(token):
    if not token:
        return None
    for client_id, record in DEVICES.items():
        if secrets.compare_digest(record.get("deviceToken", ""), token):
            return client_id, record
    return None


# --- helpers -------------------------------------------------------------------------------------

def is_trusted(peer_ip):
    if not TRUSTED_NETWORKS:
        return False
    try:
        addr = ipaddress.ip_address(peer_ip)
    except ValueError:
        return False
    return any(addr in net for net in TRUSTED_NETWORKS)


def safe_key(client_id, filename):
    """
    Namespace uploads per device and refuse anything that could escape the prefix.

    Validates the name as given rather than basename()-ing it first: silently rewriting
    "../../etc/passwd" to "passwd" would accept a request that should plainly be an error.
    """
    if (
        not filename
        or filename in (".", "..")
        or "/" in filename
        or "\\" in filename
        or filename.startswith(".")
    ):
        raise ValueError("bad filename")
    return f"{client_id}/{filename}"


class Handler(BaseHTTPRequestHandler):
    server_version = "lr-control-plane"

    def _send(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _bearer(self):
        header = self.headers.get("Authorization", "")
        return header[7:] if header.startswith("Bearer ") else None

    def _body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length))
        except json.JSONDecodeError:
            return {}

    def _client_ip(self):
        # Behind a reverse proxy the real address arrives in X-Forwarded-For.
        forwarded = self.headers.get("X-Forwarded-For")
        if forwarded:
            return forwarded.split(",")[0].strip()
        return self.client_address[0]

    def do_POST(self):
        if self.path.split("?")[0] == "/api/v1/enroll":
            return self.handle_enroll()
        if self.path.split("?")[0] == "/api/v1/presign":
            return self.handle_presign()
        self._send({"error": "not found"}, 404)

    def do_GET(self):
        if self.path.split("?")[0] == "/api/v1/config":
            return self.handle_config()
        if self.path.split("?")[0] == "/healthz":
            return self._send({"ok": True})
        self._send({"error": "not found"}, 404)

    def handle_enroll(self):
        token = self._bearer()
        body = self._body()
        client_id = str(body.get("clientId", "")).strip()
        device_name = str(body.get("deviceName", "unknown"))[:100]

        if not client_id:
            return self._send({"error": "clientId required"}, 400)

        if token:
            if token not in ENROLLMENT_TOKENS:
                self.log_line(f"enroll DENIED bad token client={client_id}")
                return self._send({"error": "invalid enrollment token"}, 403)
        elif not is_trusted(self._client_ip()):
            # This is the client's well-known probe from an untrusted network. Refusing here is
            # what makes the app fall back to asking for a QR scan.
            self.log_line(f"enroll DENIED untrusted {self._client_ip()} client={client_id}")
            return self._send({"error": "enrollment token required"}, 403)

        device_token = secrets.token_urlsafe(32)
        DEVICES[client_id] = {
            "deviceToken": device_token,
            "deviceName": device_name,
            "enrolledAt": int(time.time()),
        }
        save_devices(DEVICES)
        self.log_line(f"enroll OK client={client_id} name={device_name}")

        self._send({"deviceToken": device_token, "format": FORMAT, "retention": RETENTION})

    def handle_config(self):
        if device_for_token(self._bearer()) is None:
            return self._send({"error": "unauthorized"}, 401)
        self._send({"format": FORMAT, "retention": RETENTION})

    def handle_presign(self):
        found = device_for_token(self._bearer())
        if found is None:
            return self._send({"error": "unauthorized"}, 401)

        client_id, _ = found
        body = self._body()
        size = int(body.get("sizeBytes", 0))
        if size <= 0 or size > MAX_UPLOAD_BYTES:
            return self._send({"error": "bad sizeBytes"}, 400)

        try:
            key = safe_key(client_id, str(body.get("filename", "")))
        except ValueError:
            return self._send({"error": "bad filename"}, 400)

        url = s3.generate_presigned_url(
            "put_object",
            Params={
                "Bucket": S3_BUCKET,
                "Key": key,
                "ContentType": str(body.get("contentType", "application/octet-stream")),
            },
            ExpiresIn=PRESIGN_TTL_SECONDS,
        )
        self.log_line(f"presign OK client={client_id} key={key} size={size}")
        self._send({"uploadUrl": url, "expiresAt": int(time.time()) + PRESIGN_TTL_SECONDS})

    def log_line(self, message):
        print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {message}", flush=True)

    def log_message(self, *args):
        pass  # quiet the default per-request access log


if __name__ == "__main__":
    print(f"LR control plane on :{LISTEN_PORT}", flush=True)
    print(f"  bucket={S3_BUCKET} via {S3_PUBLIC_ENDPOINT}", flush=True)
    print(f"  well-known enrollment from: {TRUSTED_NETWORKS or 'DISABLED (QR only)'}", flush=True)
    print(f"  enrollment tokens configured: {len(ENROLLMENT_TOKENS)}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), Handler).serve_forever()
