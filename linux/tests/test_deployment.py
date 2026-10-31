"""Deployment artifact checks; no installation or target host mutation."""

from __future__ import annotations

import os
import shutil
import subprocess
from datetime import datetime
from pathlib import Path

import pytest

LINUX = Path(__file__).parents[1]
SCRIPT = LINUX / "deploy" / "install.sh"


def test_units_use_dedicated_account_explicit_paths_and_bounded_restarts() -> None:
    for name in ("tracky", "tracky-sync"):
        unit = (LINUX / "systemd" / f"{name}.service").read_text()
        for setting in (
            "User=tracky", "Group=tracky", "StateDirectory=tracky",
            "StateDirectoryMode=0750", "UMask=0027", "Restart=always",
            "StartLimitIntervalSec=0", "NoNewPrivileges=true",
            "ProtectSystem=strict", "ProtectHome=true", "PrivateTmp=true",
            "CapabilityBoundingSet=", "TimeoutStopSec=",
            "--db-path /var/lib/tracky/tracky.sqlite3",
        ):
            assert setting in unit
        assert "ExecStart=/opt/tracky/venv/bin/" in unit
        assert "/root" not in unit
    daemon = (LINUX / "systemd" / "tracky.service").read_text()
    assert "After=bluetooth.service" in daemon
    sync = (LINUX / "systemd" / "tracky-sync.service").read_text()
    assert "ReadOnlyPaths=/var/lib/tracky" in sync
    for flag, path in (
        ("--token-file", "token"), ("--profile", "profile.toml"),
        ("--tls-cert-file", "tls/server.crt"), ("--tls-key-file", "tls/server.key"),
    ):
        assert f"{flag} /etc/tracky/{path}" in sync


def test_install_script_shell_syntax_and_readable_venv_umask() -> None:
    bash = shutil.which("bash")
    if bash is None:
        pytest.skip("bash unavailable")
    subprocess.run([bash, "-n", str(SCRIPT)], check=True, capture_output=True)
    script = SCRIPT.read_text()
    assert "set -euo pipefail" in script
    assert "umask 022\npython3 -m venv" in script
    assert 'usermod --append --groups tracky "$SUDO_USER"' in script


def test_certificate_provisioning_has_ip_sans_ca_constraints_and_is_idempotent(
    tmp_path: Path,
) -> None:
    bash, openssl = shutil.which("bash"), shutil.which("openssl")
    if bash is None or openssl is None:
        pytest.skip("bash/openssl unavailable")
    script = SCRIPT.read_text()
    # Execute the real issuance block only, with installation confined to tmp.
    block = script.split("# Do not inherit distro OpenSSL extensions", 1)[1]
    block = "# Do not inherit distro OpenSSL extensions" + block.split(
        "# Installed code must be readable/executable", 1
    )[0]
    driver = """
set -euo pipefail
work=work; tls=tls; sans=IP:192.0.2.1,IP:2001:db8::1
install() { cp -- "${@: -2:1}" "${@: -1}"; }
chown() { :; }
fail() { echo "$*" >&2; exit 1; }
""" + block
    (tmp_path / "work").mkdir()
    (tmp_path / "tls").mkdir()
    env = {**os.environ, "MSYS_NO_PATHCONV": "1"}
    subprocess.run([bash, "-c", driver], cwd=tmp_path, env=env, check=True, capture_output=True)
    original = {path.name: path.read_bytes() for path in (tmp_path / "tls").iterdir()}
    subprocess.run([bash, "-c", driver], cwd=tmp_path, env=env, check=True, capture_output=True)
    assert original == {path.name: path.read_bytes() for path in (tmp_path / "tls").iterdir()}
    for name in ("ca", "server"):
        text = subprocess.run(
            [openssl, "x509", "-in", str(tmp_path / "tls" / f"{name}.crt"), "-noout", "-text"],
            check=True, capture_output=True, text=True,
        ).stdout
        if name == "ca":
            assert "CA:TRUE, pathlen:0" in text
            assert "Certificate Sign" in text
        else:
            assert "CA:FALSE" in text
            assert "TLS Web Server Authentication" in text
            assert "IP Address:192.0.2.1" in text
            assert "IP Address:2001:DB8:0:0:0:0:0:1" in text
        dates = subprocess.run(
            [openssl, "x509", "-in", str(tmp_path / "tls" / f"{name}.crt"), "-noout", "-dates"],
            check=True, capture_output=True, text=True,
        ).stdout.splitlines()
        start, end = (
            datetime.strptime(line.split("=", 1)[1], "%b %d %H:%M:%S %Y %Z") for line in dates
        )
        assert (end - start).days == (3650 if name == "ca" else 825)
