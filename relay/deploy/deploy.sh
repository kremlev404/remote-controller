#!/usr/bin/env bash
# Builds the relay and deploys it.
#
#   docker   Relay + Caddy with Docker Compose. Caddy gets the certificate itself.
#            Without --host it starts here; with --host it copies the bundle over ssh and starts there.
#   systemd  Relay as a hardened systemd service behind nginx + certbot (Debian/Ubuntu/Fedora server).
#            Needs --host and sudo on that machine.
#   bundle   Only build the bundle into relay/build/deploy (copy it anywhere yourself).
#
# Examples:
#   ./relay/deploy/deploy.sh docker  --domain relay.example.com
#   ./relay/deploy/deploy.sh docker  --domain relay.example.com --host root@203.0.113.10
#   ./relay/deploy/deploy.sh systemd --domain relay.example.com --host admin@203.0.113.10 --email me@example.com
#
# Before the first run, point the domain's DNS A/AAAA record at the server and open ports 80 and 443.
set -euo pipefail

MODE="${1:-}"
[[ $# -gt 0 ]] && shift
DOMAIN=""
HOST=""
EMAIL=""
REMOTE_DIR="remote-controller-relay"
SKIP_BUILD=false

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    cat <<'EOF'

Options:
  --domain NAME     public relay name (required except for bundle)
  --host USER@HOST  deploy over ssh
  --email ADDRESS   Let's Encrypt account email (systemd mode)
  --dir PATH        remote folder for docker mode (default: ~/remote-controller-relay)
  --skip-build      reuse relay/build/install/relay
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --domain) DOMAIN="${2:-}"; shift 2 ;;
        --host) HOST="${2:-}"; shift 2 ;;
        --email) EMAIL="${2:-}"; shift 2 ;;
        --dir) REMOTE_DIR="${2:-}"; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
    esac
done

case "$MODE" in
    docker|systemd|bundle) ;;
    -h|--help|"") usage; exit 0 ;;
    *) echo "Unknown mode: $MODE" >&2; usage; exit 1 ;;
esac

if [[ "$MODE" != "bundle" && ! "$DOMAIN" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
    echo "--domain is required and must be a plain host name, e.g. relay.example.com" >&2
    exit 1
fi
if [[ "$MODE" == "systemd" && -z "$HOST" ]]; then
    echo "systemd mode needs --host USER@HOST" >&2
    exit 1
fi

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$DEPLOY_DIR/../.." && pwd)"
INSTALL_DIR="$PROJECT_ROOT/relay/build/install/relay"
BUNDLE_DIR="$PROJECT_ROOT/relay/build/deploy"

build() {
    if [[ "$SKIP_BUILD" == false ]]; then
        echo "==> Building the relay (bundles the web page)"
        (cd "$PROJECT_ROOT" && ./gradlew :relay:installDist --no-daemon)
    fi
    if [[ ! -x "$INSTALL_DIR/bin/relay" ]]; then
        echo "Relay build not found at $INSTALL_DIR" >&2
        exit 1
    fi
}

make_bundle() {
    echo "==> Bundle in $BUNDLE_DIR"
    rm -rf "$BUNDLE_DIR"
    mkdir -p "$BUNDLE_DIR"
    cp -R "$INSTALL_DIR" "$BUNDLE_DIR/relay"
    cp "$DEPLOY_DIR/Dockerfile" "$DEPLOY_DIR/docker-compose.yml" "$DEPLOY_DIR/Caddyfile" "$BUNDLE_DIR/"
    cp -R "$DEPLOY_DIR/nginx" "$DEPLOY_DIR/systemd" "$BUNDLE_DIR/"
    cp "$DEPLOY_DIR/relay.env.example" "$BUNDLE_DIR/"
    if [[ -n "$DOMAIN" ]]; then
        printf 'RELAY_DOMAIN=%s\n' "$DOMAIN" > "$BUNDLE_DIR/.env"
        sed "s/RELAY_DOMAIN/$DOMAIN/g" "$DEPLOY_DIR/nginx/relay.conf" > "$BUNDLE_DIR/nginx/relay.conf"
        sed "s/RELAY_DOMAIN/$DOMAIN/g" "$DEPLOY_DIR/nginx/acme.conf" > "$BUNDLE_DIR/nginx/acme.conf"
        printf 'RELAY_ORIGIN=https://%s\n' "$DOMAIN" > "$BUNDLE_DIR/systemd/remote-controller-relay.env"
    fi
}

copy_to_host() {
    local target="$1"
    echo "==> Copying the bundle to $HOST:$target"
    ssh "$HOST" "mkdir -p '$target'"
    if command -v rsync >/dev/null 2>&1; then
        rsync -az --delete "$BUNDLE_DIR/" "$HOST:$target/"
    else
        tar -C "$BUNDLE_DIR" -czf - . | ssh "$HOST" "tar -C '$target' -xzf -"
    fi
}

deploy_docker() {
    if [[ -z "$HOST" ]]; then
        echo "==> Starting relay + Caddy here"
        (cd "$BUNDLE_DIR" && docker compose up -d --build)
    else
        copy_to_host "$REMOTE_DIR"
        echo "==> Starting relay + Caddy on $HOST"
        ssh "$HOST" "cd '$REMOTE_DIR' && docker compose up -d --build"
    fi
    echo
    echo "Relay: https://$DOMAIN  (the first certificate can take a minute)"
    echo "Logs:  docker compose logs -f   (in $( [[ -z "$HOST" ]] && echo "$BUNDLE_DIR" || echo "$HOST:$REMOTE_DIR" ))"
}

deploy_systemd() {
    local staging="/tmp/remote-controller-relay-bundle"
    copy_to_host "$staging"
    echo "==> Installing on $HOST (sudo may ask for your password)"
    ssh -t "$HOST" "sudo bash '$staging/systemd/install.sh' '$DOMAIN' '$EMAIL' && rm -rf '$staging'"
    echo
    echo "Relay: https://$DOMAIN"
    echo "Logs:  ssh $HOST journalctl -u remote-controller-relay -f"
}

build
make_bundle
case "$MODE" in
    bundle) echo "Bundle ready: $BUNDLE_DIR" ;;
    docker) deploy_docker ;;
    systemd) deploy_systemd ;;
esac
