# LR server

A self-hosted endpoint for LR recordings: **MinIO** for storage, a small **control plane** for
enrollment and presigned URLs, and **Caddy** for automatic HTTPS.

Audio never passes through the control plane. The phone asks it for a short-lived presigned URL
and then PUTs the file straight to object storage, so the process handling requests stays tiny and
the app never holds long-lived storage credentials.

## Setup

> Deploying to a fresh Linux box? Follow **[DEPLOY.md](DEPLOY.md)** instead — it covers Docker
> install, firewall, DNS/TLS and day-2 operations step by step.

```bash
cd server
cp .env.example .env

# a strong storage password and at least one enrollment token
openssl rand -base64 32 | tr -d '=+/'   # -> MINIO_ROOT_PASSWORD
openssl rand -base64 24 | tr -d '=+/'   # -> LR_ENROLLMENT_TOKENS

$EDITOR .env      # set LR_DOMAIN to a hostname whose DNS A record points at this machine
docker compose up -d --build
```

Caddy obtains a Let's Encrypt certificate on first start, so ports 80 and 443 must be reachable
from the internet and DNS must already resolve. Check it came up:

```bash
curl https://lr.example.com/healthz     # -> {"ok": true}
docker compose logs -f control-plane
```

## Connecting a phone

```bash
pip install "qrcode[pil]"
python3 make_qr.py https://lr.example.com <one-of-your-enrollment-tokens>
```

That prints a QR code in the terminal and writes `lr-enroll.png`. In LR, tap **Scan QR code** on
first launch (or Settings → *Re-scan QR code*). The app enrolls, receives a device token, and
picks up your recording format and retention settings.

### Skipping the QR on a trusted network

Set `LR_TRUSTED_NETWORKS` to the CIDRs your phones use, e.g. `10.0.0.0/8,192.168.0.0/16`. Devices
on those networks enroll silently on first launch, with no QR. Build the app with the host baked
in so it knows where to probe:

```bash
./gradlew installDebug -PWELL_KNOWN_HOST=https://lr.example.com
```

From anywhere else the probe is refused and the app falls back to asking for a QR scan. Leave
`LR_TRUSTED_NETWORKS` blank to require a QR for every device.

## What the app expects

| Call | Auth | Purpose |
|---|---|---|
| `POST /api/v1/enroll` | enrollment token, or none from a trusted network | issues a device token + settings |
| `GET /api/v1/config` | device token | refreshed daily |
| `POST /api/v1/presign` | device token | returns a presigned PUT URL |
| `PUT <uploadUrl>` | the signed URL itself | the audio bytes |

Recordings land in the bucket under `<clientId>/<filename>`, e.g.
`b8ce4f77-.../20260802_015711.m4a`.

## Settings you push to devices

Changing these in `.env` and restarting takes effect on every phone within a day, without
re-enrolling — the app refreshes config daily.

| Variable | Meaning |
|---|---|
| `LR_CODEC` | `aac` (records `.m4a`) or `opus` (records `.ogg`) |
| `LR_BITRATE`, `LR_SAMPLE_RATE` | encoder settings |
| `LR_DAYS_UNTIL_TRASH` | uploaded recordings move to the recycle bin after this long |
| `LR_DAYS_UNTIL_PURGE` | recycle bin is emptied after this long |

## Security notes

- **TLS is required.** The app refuses cleartext except to loopback in debug builds. Caddy handles
  certificates automatically; don't put this behind plain HTTP.
- **Change `MINIO_ROOT_PASSWORD`** before exposing anything. The compose file also blocks anonymous
  bucket access, so objects are only reachable through presigned URLs.
- **Enrollment tokens are reusable** as written here. Anyone holding one can enroll a device, so
  treat a QR code like a password. If you need one-time codes, delete the token from
  `LR_ENROLLMENT_TOKENS` after the device enrolls.
- **The server can read every recording.** That was the deliberate trade-off when we chose "my own
  trusted box" — there is no end-to-end encryption. If the host ever stops being trusted, the app
  needs a client-side encryption layer before upload.
- **Revoking a device** means deleting its entry from the control plane's `devices.json`
  (`docker compose exec control-plane cat /data/devices.json`) and restarting the service.
- Consider putting Cloudflare in front for DDoS protection and rate limiting; the free tier works
  with any self-hosted origin.

## This is a reference

`control_plane.py` is deliberately small enough to read in one sitting. Before relying on it
heavily, add rate limiting on `/api/v1/enroll`, real logging, and a datastore instead of a JSON
file.
