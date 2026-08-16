# Deploying the LR server on a Linux machine

Step by step, from a bare Ubuntu/Debian box to a phone uploading recordings.

Commands assume Ubuntu 22.04+ or Debian 12+. On RHEL/Fedora swap `apt` for `dnf` and `ufw` for
`firewalld`.

---

## 0. Before you start

You need:

- A Linux machine (1 vCPU / 1 GB RAM is enough; disk sized for your recordings — roughly
  **45 MB per hour** of audio at the default 96 kbps).
- **A hostname with a real TLS certificate.** The app refuses plain HTTP, so pick one:
  - **Public domain** — a DNS **A record** pointing at the machine's public IP, with ports **80**
    and **443** reachable. Caddy then gets a Let's Encrypt certificate automatically.
  - **LAN-only machine** — a public DNS name can point at a private IP and still get a real
    certificate via a DNS-01 challenge. See [§8](#8-a-lan-only-server-getting-a-working-domain--certificate).
- Root or `sudo`.

If you're using a public domain, check DNS resolves *before* going further — Caddy will loop on
certificate failures otherwise:

```bash
dig +short lr.example.com     # must print your server's public IP
```

---

## 1. Install Docker

```bash
sudo apt update
sudo apt install -y ca-certificates curl git

sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

Let your user run Docker without `sudo` (log out and back in afterwards):

```bash
sudo usermod -aG docker $USER
newgrp docker
docker run --rm hello-world      # should succeed
```

> On Debian replace `ubuntu` with `debian` in both URLs above.

---

## 2. Open the firewall

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
sudo ufw status
```

Only 22, 80 and 443 should be open. MinIO (9000/9001) and the control plane (8080) stay on the
internal Docker network and must **not** be exposed.

> **LAN-only?** Don't open 80/443 to the internet. See [§8](#8-a-lan-only-server-getting-a-working-domain--certificate)
> for the LAN firewall rules — DNS-01 needs no inbound ports at all.

---

## 3. Get the files onto the machine

```bash
git clone https://github.com/aevdokimenko/voice-recorder.git
cd voice-recorder/server
```

Or, if the repo is private and you'd rather not put credentials on the server, copy just this
directory from your laptop:

```bash
# run on your laptop
scp -r server/ user@lr.example.com:~/lr-server/
```

---

## 4. Configure

```bash
cp .env.example .env
```

Generate two secrets and keep them somewhere safe:

```bash
echo "storage password: $(openssl rand -base64 32 | tr -d '=+/')"
echo "enrollment token: $(openssl rand -base64 24 | tr -d '=+/')"
```

Edit `.env`:

```bash
nano .env
```

```ini
LR_DOMAIN=lr.example.com
LR_ENROLLMENT_TOKENS=<the enrollment token you just generated>

# Leave blank so every device must scan a QR. See §7 to enable silent enrollment.
LR_TRUSTED_NETWORKS=

LR_CODEC=aac
LR_BITRATE=96000
LR_SAMPLE_RATE=44100
LR_DAYS_UNTIL_TRASH=15
LR_DAYS_UNTIL_PURGE=30

MINIO_ROOT_USER=lr-admin
MINIO_ROOT_PASSWORD=<the storage password you just generated>
LR_S3_BUCKET=recordings
```

Lock it down — this file holds two passwords:

```bash
chmod 600 .env
```

---

## 5. Start

```bash
docker compose up -d --build
docker compose ps            # minio, control-plane and caddy should be "running"
```

The first start pulls images and requests a certificate; give it a minute. Watch it happen:

```bash
docker compose logs -f caddy
```

You want a line mentioning `certificate obtained successfully`. If instead you see repeated ACME
errors, DNS or port 80 is wrong — go back to §0.

Verify:

```bash
curl https://lr.example.com/healthz
# {"ok": true}
```

If that returns JSON over HTTPS with no certificate warning, the server is up.

---

## 6. Connect a phone

Generate the enrollment QR. You can do this on the server:

```bash
sudo apt install -y python3-pip
pip3 install "qrcode[pil]"
python3 make_qr.py https://lr.example.com <your enrollment token>
```

It prints a scannable QR straight into the terminal and also writes `lr-enroll.png`.

On the phone: install LR, launch it, grant the microphone permission, tap **Scan QR code**, and
point it at the screen. You should see *Connected*.

Confirm the server saw it:

```bash
docker compose logs control-plane | tail
# [..] enroll OK client=<uuid> name=<phone model>
```

Then record something in the app. Within a few seconds the badge should read **Uploaded**, and:

```bash
docker compose logs control-plane | grep presign | tail -1
docker compose exec minio mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
docker compose exec minio mc ls --recursive local/recordings
```

Files land under `<clientId>/<filename>`.

---

## 7. Optional: silent enrollment on your own network

If the phones sit on a network you control, they can enrol with no QR at all. Put those ranges in
`.env`:

```ini
LR_TRUSTED_NETWORKS=10.0.0.0/8,192.168.0.0/16
```

```bash
docker compose up -d
```

Then build the app with the host baked in, so it knows where to probe:

```bash
./gradlew installDebug -PWELL_KNOWN_HOST=https://lr.example.com
```

From those networks the app enrols on first launch with no interaction. From anywhere else the
probe is refused and it falls back to the QR screen.

> If the server sits behind a reverse proxy or CDN, the trusted-network check reads
> `X-Forwarded-For`. Make sure the proxy sets it, or every device will look untrusted.

> On a LAN deployment this is the natural setup: set `LR_TRUSTED_NETWORKS` to your subnet
> (e.g. `192.168.1.0/24`) and phones on Wi-Fi enrol with no QR code at all.

---

## 8. A LAN-only server: getting a working domain + certificate

DNS is the easy half. The hard half is the **certificate** — the app refuses plain HTTP, and
Android will not trust a self-signed certificate without extra steps. Two workable paths.

---

### Option A — public DNS name pointing at your private IP (recommended)

This is legitimate and common: you publish an `A` record in public DNS that resolves to a private
address. The name is public, the machine is not. Because ownership is proven over DNS rather than
an inbound HTTP request, you get a **real, publicly-trusted certificate** and the phones need no
configuration at all.

**Requirements:** a domain you own (about $10/year) hosted at a DNS provider with an API —
Cloudflare, deSEC, DigitalOcean, Route53 and ~50 others are supported.

#### A1. Give the box a static LAN address

Either reserve it by MAC in your router's DHCP settings, or set it statically:

```bash
ip -4 addr show scope global | grep inet     # note the current address, e.g. 192.168.1.50
```

#### A2. Create the DNS record

At your DNS provider, add an `A` record pointing at that private address:

```
lr.internal.example.com.   A   192.168.1.50
```

Verify from your laptop (it resolves publicly even though the target is private):

```bash
dig +short lr.internal.example.com      # -> 192.168.1.50
```

> Cloudflare users: set the record to **DNS only** (grey cloud), not proxied. A proxied record
> would try to route traffic through Cloudflare, which cannot reach your LAN.

#### A3. Create a scoped DNS API token

In Cloudflare: **My Profile → API Tokens → Create Token → Edit zone DNS**, scoped to just this
zone. Keep it to DNS-edit on one zone — this token can only touch DNS records.

Add it to `.env`:

```ini
LR_DOMAIN=lr.internal.example.com
CLOUDFLARE_API_TOKEN=<the token>
```

#### A4. Switch Caddy to the DNS-01 challenge

Two files in this directory are prepared for it:

```bash
cp Caddyfile.lan Caddyfile
```

Then in `docker-compose.yml`, change the `caddy` service to build the plugin image and pass the
token — replace `image: caddy:2` with a build, and add the variable:

```yaml
  caddy:
    build:
      context: .
      dockerfile: Dockerfile.caddy
    ports:
      - "80:80"
      - "443:443"
    environment:
      LR_DOMAIN: ${LR_DOMAIN}
      LR_S3_BUCKET: ${LR_S3_BUCKET}
      CLOUDFLARE_API_TOKEN: ${CLOUDFLARE_API_TOKEN}
```

Using a different provider? Edit the `xcaddy build --with ...` line in `Dockerfile.caddy` and the
`dns` line in `Caddyfile` to match — see <https://github.com/caddy-dns>.

#### A5. Start and verify

```bash
docker compose up -d --build
docker compose logs -f caddy          # wait for "certificate obtained successfully"
curl https://lr.internal.example.com/healthz
```

No inbound ports are needed from the internet, but the machine **does** need outbound access to
reach Let's Encrypt and your DNS API. Firewall-wise you only need port 443 open **to your LAN**:

```bash
sudo ufw allow OpenSSH
sudo ufw allow from 192.168.0.0/16 to any port 443 proto tcp
sudo ufw --force enable
```

Phones on the LAN resolve the public name to `192.168.1.50` and connect locally. Off the LAN they
simply can't reach it — which for an internal tool is usually what you want.

---

### Option B — your own internal CA, no domain at all

Free and fully offline, but it needs work on **every phone**, and one change to the app.

The catch is Android-specific: since Android 7, apps do **not** trust user-installed CA
certificates by default. Installing your root CA in Settings is not enough — LR would still reject
the connection. The app needs a `network_security_config.xml` that explicitly trusts the user
store, which is a real (small) code change, and one that slightly weakens the app's TLS posture on
every device.

Rough shape, if you want to go this way:

1. Create a CA and issue a cert for a made-up name like `lr.lan`, e.g. with
   [mkcert](https://github.com/FiloSottile/mkcert) or `step-ca`.
2. Point Caddy at those files: `tls /certs/tls.crt /certs/tls.key`.
3. Make `lr.lan` resolve on your LAN — a host override in your router, Pi-hole, or dnsmasq.
4. Install the root CA on each phone (Settings → Security → Encryption & credentials → Install a
   certificate → CA certificate).
5. Add a network security config to the app trusting user CAs, and rebuild.

**Recommendation:** unless a domain is genuinely impossible, take Option A. It costs about $10 a
year and avoids touching device trust stores or loosening the app's certificate validation.

> Tell me if you want Option B and I'll make the app-side change — it is small, but it is a
> deliberate reduction in TLS strictness and shouldn't be added silently.

## 9. Day-2 operations

**Change recording format or retention.** Edit `.env`, then:

```bash
docker compose up -d
```

Every enrolled phone picks it up within a day via its config refresh. No re-enrolment.

**See enrolled devices:**

```bash
docker compose exec control-plane cat /data/devices.json
```

**Revoke a device** — delete its entry and restart:

```bash
docker compose exec control-plane sh -c \
  "python3 -c \"import json;d=json.load(open('/data/devices.json'));d.pop('<clientId>',None);json.dump(d,open('/data/devices.json','w'))\""
docker compose restart control-plane
```

**Retire an enrollment token:** remove it from `LR_ENROLLMENT_TOKENS` in `.env` and
`docker compose up -d`. Already-enrolled devices keep working; the QR stops working.

**Back up.** Two volumes matter:

```bash
docker run --rm -v server_minio-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/minio-$(date +%F).tar.gz -C /data .
docker run --rm -v server_cp-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/devices-$(date +%F).tar.gz -C /data .
```

(Volume names are prefixed with the directory name — check with `docker volume ls`.)

**Update:**

```bash
git pull
docker compose up -d --build
```

**Logs:**

```bash
docker compose logs -f control-plane
docker compose logs -f caddy
```

---

## 10. Troubleshooting

| Symptom | Cause |
|---|---|
| Caddy loops on ACME errors | DNS not pointing here, or port 80 blocked. Check `dig +short`, `sudo ufw status`. |
| App says "Could not connect to the server" | Wrong token in the QR, or `/healthz` unreachable from the phone's network. Try the URL in the phone's browser. |
| Enrolls fine, uploads never finish | The presigned URL is signed for `LR_S3_PUBLIC_ENDPOINT`. It must be the address the *phone* can reach — `https://<domain>/s3`, not `http://minio:9000`. |
| `enroll DENIED untrusted` in logs | Expected when `LR_TRUSTED_NETWORKS` doesn't cover the phone; it will fall back to QR. |
| Uploads 403 after a while | Presigned URLs expire after `LR_PRESIGN_TTL` (default 15 min). The app requests a fresh one per attempt, so this should self-heal on retry. |
| Certificate warning on the phone | You're using a self-signed or internal CA cert without installing the CA on the device. |
