#!/usr/bin/env bash
# Runs on the server as root; deploy.sh copies the bundle and calls it over ssh:
#   sudo bash systemd/install.sh relay.example.com [email]
# Installs Java 21, the relay as a systemd service, nginx and a Let's Encrypt certificate.
# Debian, Ubuntu, Fedora and RHEL-like servers.
set -euo pipefail

DOMAIN="${1:?usage: install.sh DOMAIN [EMAIL]}"
EMAIL="${2:-}"
STAGING="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

install_package() {
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -qq && apt-get install -y "$1"
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y "$2"
    else
        echo "Install $1 and run again." >&2
        exit 1
    fi
}

command -v java >/dev/null 2>&1 || install_package openjdk-21-jre-headless java-21-openjdk-headless
command -v nginx >/dev/null 2>&1 || install_package nginx nginx
command -v certbot >/dev/null 2>&1 || install_package certbot certbot

echo "==> Relay service"
rm -rf /opt/remote-controller-relay.new
cp -R "$STAGING/relay" /opt/remote-controller-relay.new
rm -rf /opt/remote-controller-relay
mv /opt/remote-controller-relay.new /opt/remote-controller-relay
chmod -R a+rX /opt/remote-controller-relay
install -m 644 "$STAGING/systemd/remote-controller-relay.env" /etc/remote-controller-relay.env
install -m 644 "$STAGING/systemd/remote-controller-relay.service" /etc/systemd/system/remote-controller-relay.service
systemctl daemon-reload
systemctl enable remote-controller-relay
systemctl restart remote-controller-relay

echo "==> nginx and certificate"
# Fedora/RHEL: let nginx reach 127.0.0.1:8080 and open the web ports.
if command -v setsebool >/dev/null 2>&1; then setsebool -P httpd_can_network_connect 1 || true; fi
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    firewall-cmd --permanent --add-service=http --add-service=https && firewall-cmd --reload
fi
if [ -d /etc/nginx/sites-enabled ]; then
    CONF=/etc/nginx/sites-enabled/remote-controller-relay.conf
else
    CONF=/etc/nginx/conf.d/remote-controller-relay.conf
fi
mkdir -p /var/www/certbot
systemctl enable --now nginx
if [ ! -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]; then
    install -m 644 "$STAGING/nginx/acme.conf" "$CONF"
    nginx -t && systemctl reload nginx
    if [ -n "$EMAIL" ]; then ACCOUNT=(--email "$EMAIL"); else ACCOUNT=(--register-unsafely-without-email); fi
    certbot certonly --webroot -w /var/www/certbot -d "$DOMAIN" \
        --non-interactive --agree-tos "${ACCOUNT[@]}" \
        --deploy-hook "systemctl reload nginx"
fi
install -m 644 "$STAGING/nginx/relay.conf" "$CONF"
nginx -t && systemctl reload nginx

sleep 2
systemctl --no-pager --lines=5 status remote-controller-relay || true
echo "Relay: https://$DOMAIN"
