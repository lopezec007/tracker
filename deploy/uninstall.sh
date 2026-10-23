#!/usr/bin/env bash
# Remove the tracker service, unit and binary. Data and configuration are
# left in place. Usage (as root): deploy/uninstall.sh
set -euo pipefail

BIN_DST=/usr/local/bin/tracker
UNIT_DST=/etc/systemd/system/tracker.service
ENV_DIR=/etc/tracker
STATE_DIR=/var/lib/tracker
SVC_USER=tracker

[[ $EUID -eq 0 ]] || { echo "uninstall.sh: must be run as root" >&2; exit 1; }

if [[ -f "$UNIT_DST" ]]; then
    echo "==> stopping and disabling tracker.service"
    systemctl disable --now tracker.service 2>/dev/null || true
    rm -f "$UNIT_DST"
    systemctl daemon-reload
    systemctl reset-failed tracker.service 2>/dev/null || true
else
    echo "==> tracker.service not installed"
fi

if [[ -e "$BIN_DST" ]]; then
    echo "==> removing $BIN_DST"
    rm -f "$BIN_DST"
fi

cat <<MSG

tracker has been uninstalled. Left in place on purpose:
  $STATE_DIR/      the SQLite database (back it up or delete it yourself)
  $ENV_DIR/        configuration
  user '$SVC_USER' (remove with: userdel $SVC_USER)
MSG
