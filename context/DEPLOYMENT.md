# Tracky deployment

## Linux appliance

Install or update from a checkout on the Debian host:

```bash
sudo env TRACKY_HEIGHT_CM=<cm> TRACKY_SERVER_IPS=<lan-ip> bash linux/deploy/install.sh
```

The script is idempotent and preserves an existing profile, token, and CA. It:

- creates the `tracky` system account and adds the invoking user to its group;
- installs the package with BLE and MCP extras into `/opt/tracky/venv`;
- writes `/etc/tracky/profile.toml` (height only), `/etc/tracky/token`, and a
  private CA plus an IP-SAN server certificate under `/etc/tracky/tls/`;
- installs `/usr/local/bin/tracky-mcp` and the units from `linux/systemd/`, then
  enables and restarts them.

| Unit | Role |
| --- | --- |
| `tracky.service` | Acquisition daemon; sole SQLite writer; restarts on adapter or BlueZ loss |
| `tracky-sync.service` | Authenticated TLS sync API on port 8765; read-only database access |

State lives in `/var/lib/tracky` (`0750 tracky:tracky`, database files `0640`).
Members of the `tracky` group can open it read-only, which the WAL database needs
for MCP.

Host requirements:

- Allow TCP 8765 from the home LAN only (for example
  `ufw allow from <lan-cidr> to any port 8765 proto tcp`); never forward it.
- Reserve the appliance's LAN IP. The server certificate names that IP; after a
  change, delete `server.crt`/`server.key` and rerun the installer with the new IP.
  The CA and token are preserved.
- Keep the host from suspending.
- Back up `/var/lib/tracky` with SQLite's online backup (`sqlite3 ... ".backup"`).
- Renew the server certificate before its 825-day expiry the same way.

Changing the profile: edit `/etc/tracky/profile.toml` and restart
`tracky-sync.service`. Height affects every derived value; recompute historical
records by bumping `DERIVATION_VERSION`, not by editing the file alone.

## MCP

MCP runs on demand over stdio and is never a service. Launch
`/usr/local/bin/tracky-mcp` locally or through `ssh -T <host> tracky-mcp` as a member
of the `tracky` group. It opens the database read-only, derives with the same
profile, and never receives the sync token.

## Android

1. Install the APK (`adb install -r android/app/build/outputs/apk/debug/app-debug.apk`).
2. Install `/etc/tracky/tls/ca.crt` as a CA certificate in Android settings.
3. Open onboarding: `adb shell am start -n dev.eggmasonvalue.tracky/.MainActivity`.
4. Grant local network access and the Health Connect write permissions. Only weight
   is required.
5. Enter `https://<lan-ip>:8765` and the token from `/etc/tracky/token`; save and
   sync. WorkManager then syncs about every 15 minutes on any network and retries
   with backoff.

## Verification

A new install is done when a real weigh-in appears as a `complete` session from
`tracky.service`, the sync API returns it with composition, the app reports an
import, and Health Connect shows the records at the local weigh-in time.
`systemctl restart bluetooth` should restart `tracky.service` and scanning
should resume.
