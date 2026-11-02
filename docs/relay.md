# Reach from anywhere (relay)

This works like TeamViewer or RustDesk:
- The laptop keeps one **outgoing** websocket to a relay and signs the relay's challenge with its device key. Its **9-digit ID** is derived from that key, so nobody else can register the same ID.
- When a browser asks for the ID, the relay tells the laptop. The laptop opens a second outgoing socket for that session, and the relay pipes the two frame by frame.
- The relay checks Origin, rate-limits per address, and caps each laptop at 16 tunnels.

## Deploy the relay

You need a server with a public IP, a domain whose DNS A/AAAA record points at it, and ports 80 and 443 open. [`relay/deploy/deploy.sh`](../relay/deploy/deploy.sh) builds the relay on your machine and installs it in one of two ways:

| Mode | What runs on the server | Certificate |
|---|---|---|
| `docker` | relay + [Caddy](../relay/deploy/Caddyfile) with Docker Compose | Caddy gets and renews it |
| `systemd` | relay as a hardened [systemd service](../relay/deploy/systemd/remote-controller-relay.service) behind [nginx](../relay/deploy/nginx/relay.conf) | certbot gets it; its timer renews it |

```bash
# Docker on the server (needs Docker with the compose plugin there)
./relay/deploy/deploy.sh docker --domain relay.example.com --host root@203.0.113.10

# systemd + nginx on Debian, Ubuntu or Fedora (needs sudo there; installs Java 21, nginx, certbot)
./relay/deploy/deploy.sh systemd --domain relay.example.com --host admin@203.0.113.10 --email you@example.com

# Docker on this machine, or only the bundle to copy anywhere yourself
./relay/deploy/deploy.sh docker --domain relay.example.com
./relay/deploy/deploy.sh bundle --domain relay.example.com     # → relay/build/deploy
```

The bundle in `relay/build/deploy` holds the built relay, the [Dockerfile](../relay/deploy/Dockerfile) (runtime only, non-root), [docker-compose.yml](../relay/deploy/docker-compose.yml) and `.env`, and the nginx and systemd files with the domain filled in. Add `--skip-build` to reuse the last build.

What each setup locks down:
- **Relay process.** In Docker it is read-only, drops all capabilities, sets `no-new-privileges` and is capped at 512 MB. Under systemd it uses `DynamicUser`, `ProtectSystem=strict`, `ProtectHome`, no capabilities, and listens on `127.0.0.1` only.
- **nginx.** TLS 1.2/1.3 only. It **overwrites** `X-Forwarded-For` with the client address instead of appending to it, so clients cannot dodge the relay's rate limits. Websockets get a one-hour timeout.

Relay environment:

| Variable | Default | Meaning |
|---|---|---|
| `RELAY_ORIGIN` | none | Public origin, e.g. `https://relay.example.com`. Browsers from any other origin are refused. |
| `RELAY_TRUST_PROXY` | `false` | Read the client address from `X-Forwarded-For`. Only behind your own proxy. |
| `HOST`, `PORT` | `0.0.0.0`, `8080` | Listener |

Logs: `docker compose logs -f` in the bundle folder, or `journalctl -u remote-controller-relay -f`. Health check: `https://relay.example.com/healthz`.

On the laptop, turn on **Reach from anywhere**, enter `https://relay.example.com` and press **Save**. The card then shows the ID and the link to open.

Not done yet:
- The phone apps connect over the LAN only.
- All relay traffic goes through the relay. Direct peer-to-peer (WebRTC with STUN/TURN, TeamViewer's UDP hole punching) would take load off it.
