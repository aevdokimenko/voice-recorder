# LR server on the DGX box — deployment plan

Two phases, deliberately separated: **prove the flow on the LAN first**, then **expose it to the
internet** as a distinct piece of work with its own security review. Doing them together would mean
debugging enrollment, uploads, TLS, NAT and hardening all at once.

## Environment (verified 2026-08-12)

| Fact | Value |
|---|---|
| `dgx-box1.local` | **192.168.88.232** |
| `dgx-box2.local` | 192.168.88.221 |
| Dev Mac | 192.168.88.218 (same `/24`) |
| SSH | port 22 open on both; `~/.ssh/config` has `dgx1`/`dgx2` aliases, user `aevdokimenko` |
| SSH auth from this machine | **fails** — `Permission denied (publickey,password)` |

`.local` names resolve to IPv6 link-local first, which is why `ssh dgx1` reports *no route to host*.
Use the IPv4 address, or add `AddressFamily inet` to the host block.

---

## Blockers before Phase A can start

1. **SSH access.** Either authorize the dev machine's key, or confirm password auth:
   ```bash
   ssh-copy-id -i ~/.ssh/id_ed25519.pub aevdokimenko@192.168.88.232
   ```
2. **Which box**, and whether it is shared. A DGX is usually a shared GPU machine; running a
   network service on it needs to be someone's explicit call.
3. **Docker availability and permission** — whether Docker is installed, and whether the account is
   in the `docker` group or has sudo. DGX OS normally ships Docker, but access varies.
4. **Ports 80/443 free** on the box, and nothing else already bound to 8080/9000.

---

## Phase A — LAN pilot

**Goal:** a phone on the office Wi-Fi records, uploads, and the file lands in MinIO on the DGX.

### A1. TLS decision for the pilot

The app requires HTTPS, and a LAN box has no certificate. Rather than solve TLS and the flow at
the same time, the pilot runs over **plain HTTP with a debug-only exemption**: add
`192.168.88.232` to `app/src/debug/res/xml/network_security_config.xml`, which already whitelists
loopback for emulator testing.

- Affects **debug builds only**. Release builds still reject cleartext outright.
- Must never be extended to a release source set. Phase B replaces it with a real certificate.

The alternative — do DNS-01 TLS immediately — is strictly better security but couples two
unrelated failure domains. Recommendation: HTTP for the pilot, TLS before anything leaves the LAN.

### A2. Deploy

1. Copy `server/` to the box (`scp -r server/ aevdokimenko@192.168.88.232:~/lr-server/`).
2. `cp .env.example .env`; set `LR_DOMAIN=192.168.88.232`, generate `MINIO_ROOT_PASSWORD` and
   `LR_ENROLLMENT_TOKENS`, `chmod 600 .env`.
3. Swap Caddy for plain HTTP on the pilot: site address `:80` instead of `{$LR_DOMAIN}`, so it
   serves the IP without attempting a certificate.
4. `docker compose up -d --build`.
5. Verify from the Mac: `curl http://192.168.88.232/healthz` → `{"ok": true}`.

### A3. Firewall

Allow 80 only from the LAN; keep MinIO and the control plane off the host network.

```bash
sudo ufw allow OpenSSH
sudo ufw allow from 192.168.88.0/24 to any port 80 proto tcp
```

### A4. Connect the phone

Two routes, both worth exercising since Phase B will use one of them:

- **Silent enrollment:** set `LR_TRUSTED_NETWORKS=192.168.88.0/24`, build with
  `-PWELL_KNOWN_HOST=http://192.168.88.232`, launch — enrolls with no interaction.
- **QR enrollment:** leave the trusted network blank, run `make_qr.py`, scan.

### A5. Verify the whole flow

1. `docker compose logs control-plane` shows `enroll OK`.
2. Record → badge reaches **Uploaded**.
3. `presign OK` in the logs.
4. File present: `mc ls --recursive local/recordings` under `<clientId>/<filename>`.
5. Recording format matches `LR_CODEC` (`.m4a` for aac, `.ogg` for opus).
6. Airplane-mode test: record offline, re-enable network, upload completes on its own.
7. Kill the app mid-upload; it still completes (WorkManager durability).

**Exit criterion:** a recording made on the phone is byte-identical in MinIO, with the badge
showing Uploaded.

---

## Phase B — internet exposure

Separate work, and the point at which security stops being theoretical.

### B0. Decide *what* to expose — the important question

Putting an internet-facing service directly on a shared GPU box is worth avoiding. Options, best
first:

1. **Cloudflare Tunnel (or similar) from the DGX.** No inbound ports, no NAT changes, no public IP
   on the box; Cloudflare terminates TLS and absorbs volumetric attacks. The DGX keeps zero
   listening sockets exposed to the internet.
2. **Small separate VPS as the public edge**, reverse-proxying to the DGX over WireGuard. Costs a
   few dollars a month and keeps the blast radius off the GPU box entirely.
3. **Port-forward 443 to the DGX.** Simplest, and the one I would argue against: it puts a shared
   research machine directly on the internet.

### B1. Real TLS

A public domain, with either HTTP-01 (if 80 is reachable) or DNS-01 (if not — see
`server/DEPLOY.md` §8 A, plus `Dockerfile.caddy` and `Caddyfile.lan`). Then **remove the
debug cleartext exemption for the DGX IP** added in A1.

### B2. Harden the control plane before exposure

`control_plane.py` is a reference implementation. Internet exposure needs, at minimum:

- **Rate limiting on `/api/v1/enroll`** — currently unbounded, and it is the only unauthenticated
  endpoint when a trusted network is configured.
- **One-time enrollment tokens.** Today a token is reusable indefinitely; a leaked QR is a
  permanent enrolment key.
- **Device-token expiry / rotation.** Tokens are currently immortal.
- **Structured request logging** with source IP, for any forensics.
- **A real datastore** instead of `devices.json` (concurrent writes are not safe under load).
- **`LR_TRUSTED_NETWORKS` must be empty or private-only** once public — otherwise a spoofed
  `X-Forwarded-For` could bypass enrollment. Verify the proxy overwrites rather than appends it.

### B3. Storage exposure review

Confirm the bucket stays non-public (`mc anonymous set none` runs at init, re-verify), presigned
TTL is short (default 15 min), and MinIO's console port 9001 is not routable from outside.

### B4. Re-verify end to end from off-LAN

Repeat A5 over cellular with Wi-Fi disabled.

---

## Sequencing

Phase A is a day's work once SSH is sorted. Phase B should not start until A's exit criterion is
met, and B2 should be treated as required rather than optional — the current control plane is
comfortable on a LAN and not ready for the open internet.
