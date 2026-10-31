# Current implementation status

## Deployed

- The Debian appliance runs `tracky.service` (acquisition) and `tracky-sync.service`
  (HTTPS sync API) as the dedicated `tracky` user, installed by
  `linux/deploy/install.sh`. The database is `/var/lib/tracky/tracky.sqlite3`.
- The Android bridge is installed on the owner's phone and imports over the home LAN
  through a private CA.
- A live weigh-in has completed the full path: scale → daemon → sync API → Android
  → Health Connect, including derived records.
- Restarting BlueZ makes the daemon exit and systemd restart it within seconds.

## Linux

- Bleak active scanning on BlueZ; the daemon probes BlueZ and adapter state every
  30 seconds and exits on failure so systemd restarts it.
- Session creation and operation binding are atomic; startup recovery aborts every
  open session, so an interrupted acquisition cannot stall sync.
- BLE teardown has hard deadlines.
- Quality is versioned and independent of lifecycle: `accepted`, `contact_failed`,
  or `indeterminate`. Bursts with any zero word are not `accepted`.
- Weight-selection policy 1 persists a frame after three repeated, positive,
  wire-valid display-weight observations.
- Derivation version 2 is vendor-compatible and parity-tested; see `DERIVATION.md`.

## Sync API and MCP

- Contract version 2 carries Linux-derived composition; see `contracts/README.md`.
- The sync API requires a bearer token and TLS, opens SQLite read-only, and never
  advances past an open session.
- MCP runs on demand over stdio via `/usr/local/bin/tracky-mcp` with `--db-path` and
  `--profile`; members of the `tracky` group can open the WAL database read-only.

## Android

- No derivation, Bluetooth, or Health Connect reads. It writes weight plus
  body fat, lean body mass, body water mass, bone mass, and basal metabolic rate.
- Only the weight write permission is required; Android 17 local-network access is
  requested at onboarding. The app trusts system and user-installed CAs.
- Onboarding has no launcher icon; open it with
  `adb shell am start -n dev.eggmasonvalue.tracky/.MainActivity` or through Health
  Connect's app permissions.

## Known limits

- The current scale unit shows Err2 on every handle weigh-in and reports an
  inverted trunk pair; derivation corrects it (`trunk_order: transposed`). A
  warranty replacement is pending; check its trunk ordering first.
- The server certificate is bound to the appliance's LAN IP; keep that IP reserved.
- Sync cursors assume a monotonic wall clock.
- There is no root CI yet.

## Verification

- Linux: `uv run pytest` and `uv run ruff check src tests` pass.
- Android: `./gradlew testDebugUnitTest lintDebug assembleDebug` passes.
