#!/usr/bin/env bash
# Run on Debian from a checkout: sudo env TRACKY_HEIGHT_CM=... TRACKY_SERVER_IPS=... bash linux/deploy/install.sh
# IPs may be comma/space-separated. Existing profile, token and CA are preserved.
set -euo pipefail
umask 077

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail 'Run this script as root via sudo.'
[[ -n ${SUDO_USER:-} && $SUDO_USER != root ]] || fail 'SUDO_USER must name the human MCP user.'
getent passwd "$SUDO_USER" >/dev/null || fail 'SUDO_USER is not a local account.'
for command in python3 openssl systemctl; do
    command -v "$command" >/dev/null || fail "Install $command first (Python also needs python3-venv)."
done
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
config=/etc/tracky
tls=$config/tls

# Validate inputs before changing the machine. Never fabricate profile data/SANs.
height=''
if [[ ! -f $config/profile.toml ]]; then
    [[ -n ${TRACKY_HEIGHT_CM:-} ]] || fail 'Set TRACKY_HEIGHT_CM to your measured height in cm.'
    height=$(python3 - "$TRACKY_HEIGHT_CM" <<'PY'
import math
import sys
value = float(sys.argv[1])
if not math.isfinite(value) or not 0 < value < 300:
    sys.exit("TRACKY_HEIGHT_CM must be a finite height in cm between 0 and 300")
print(value)
PY
    )
fi
sans=''
if [[ ! -f $tls/server.crt || ! -f $tls/server.key ]]; then
    [[ -n ${TRACKY_SERVER_IPS:-} ]] || fail 'Set TRACKY_SERVER_IPS to the LAN IP(s) used by the phone.'
    sans=$(python3 - "$TRACKY_SERVER_IPS" <<'PY'
import ipaddress
import sys
ips = sys.argv[1].replace(",", " ").split()
if not ips:
    sys.exit("TRACKY_SERVER_IPS must contain at least one IP address")
print(",".join("IP:" + str(ipaddress.ip_address(ip)) for ip in ips))
PY
    )
fi
if [[ -f $tls/ca.crt && ! -f $tls/ca.key || -f $tls/ca.key && ! -f $tls/ca.crt ]]; then
    fail 'Incomplete private CA: restore the missing CA file; do not silently replace phone trust.'
fi

getent group tracky >/dev/null || groupadd --system tracky
id tracky >/dev/null 2>&1 || useradd --system --gid tracky --home-dir /var/lib/tracky --no-create-home --shell /usr/sbin/nologin tracky
usermod --append --groups tracky "$SUDO_USER"
install -d -o root -g root -m 0755 /opt/tracky
install -d -o root -g tracky -m 0750 "$config" "$tls"
install -d -o tracky -g tracky -m 0750 /var/lib/tracky
for file in /var/lib/tracky/tracky.sqlite3{,-wal,-shm}; do
    if [[ -f $file ]]; then chown tracky:tracky "$file"; chmod 0640 "$file"; fi
done
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
if [[ ! -f $config/token ]]; then
    openssl rand -hex 32 >"$work/token"
    install -o root -g tracky -m 0640 "$work/token" "$config/token"
fi
if [[ ! -f $config/profile.toml ]]; then
    printf 'height_cm = %s\n' "$height" >"$work/profile.toml"
    install -o root -g tracky -m 0640 "$work/profile.toml" "$config/profile.toml"
fi
chown root:tracky "$config/token" "$config/profile.toml"
chmod 0640 "$config/token" "$config/profile.toml"

# Do not inherit distro OpenSSL extensions (which can duplicate CA constraints).
printf '%s\n' '[req]' 'distinguished_name=dn' '[dn]' >"$work/req.cnf"
if [[ ! -f $tls/ca.crt ]]; then
    openssl req -config "$work/req.cnf" -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
        -subj '/CN=Tracky private CA' \
        -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
        -addext 'keyUsage=critical,keyCertSign,cRLSign' \
        -addext 'subjectKeyIdentifier=hash' \
        -keyout "$work/ca.key" -out "$work/ca.crt" 2>/dev/null
    install -o root -g root -m 0600 "$work/ca.key" "$tls/ca.key"
    install -o root -g tracky -m 0640 "$work/ca.crt" "$tls/ca.crt"
fi
if [[ ! -f $tls/server.crt || ! -f $tls/server.key ]]; then
    openssl req -config "$work/req.cnf" -new -newkey rsa:3072 -nodes -sha256 \
        -subj '/CN=Tracky sync' \
        -keyout "$work/server.key" -out "$work/server.csr" 2>/dev/null
    printf '%s\n' 'basicConstraints=critical,CA:FALSE' \
        'keyUsage=critical,digitalSignature,keyEncipherment' \
        'extendedKeyUsage=serverAuth' 'subjectKeyIdentifier=hash' \
        'authorityKeyIdentifier=keyid,issuer' "subjectAltName=$sans" >"$work/server.ext"
    openssl x509 -req -sha256 -days 825 -in "$work/server.csr" \
        -CA "$tls/ca.crt" -CAkey "$tls/ca.key" -set_serial "0x$(openssl rand -hex 16)" \
        -extfile "$work/server.ext" -out "$work/server.crt"
    install -o root -g tracky -m 0640 "$work/server.key" "$tls/server.key"
    install -o root -g tracky -m 0640 "$work/server.crt" "$tls/server.crt"
fi
chown root:root "$tls/ca.key"; chmod 0600 "$tls/ca.key"
chown root:tracky "$tls/ca.crt" "$tls/server.crt" "$tls/server.key"
chmod 0640 "$tls/ca.crt" "$tls/server.crt" "$tls/server.key"
openssl verify -purpose sslserver -CAfile "$tls/ca.crt" "$tls/server.crt"
openssl x509 -checkend 86400 -noout -in "$tls/server.crt" || fail 'Server certificate expires soon: remove server.crt only and rerun with TRACKY_SERVER_IPS to renew under the same CA.'

# Installed code must be readable/executable by the service and MCP accounts.
umask 022
python3 -m venv /opt/tracky/venv
/opt/tracky/venv/bin/python -m pip install --upgrade "$repo/linux[ble,mcp]"
install -o root -g root -m 0644 "$repo/linux/systemd/tracky.service" "$repo/linux/systemd/tracky-sync.service" /etc/systemd/system/
printf '%s\n' '#!/bin/sh' 'exec /opt/tracky/venv/bin/tracky-mcp --db-path /var/lib/tracky/tracky.sqlite3 --profile /etc/tracky/profile.toml "$@"' >"$work/tracky-mcp"
install -o root -g root -m 0755 "$work/tracky-mcp" /usr/local/bin/tracky-mcp
systemctl daemon-reload
systemctl enable tracky.service tracky-sync.service
systemctl restart tracky.service tracky-sync.service
printf '\nCA for phone installation: %s\nRead bearer token: sudo cat %s\n' "$tls/ca.crt" "$config/token"
printf 'Open a new login/SSH session for tracky group membership, then launch tracky-mcp on demand.\n'
printf 'Allow TCP 8765 only from the intended LAN/client; no firewall rule was changed.\n'
printf 'Renew server.crt before its 825-day expiry; preserve ca.crt/ca.key and the token.\n'
