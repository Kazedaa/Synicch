#!/usr/bin/env bash
# Push the server code to the server. Re-runnable; overwrites source only.
# Set SYNICCH_HOST to your own box, e.g. SYNICCH_HOST=root@10.0.0.5 ./deploy.sh
set -euo pipefail

HOST="${SYNICCH_HOST:?set SYNICCH_HOST, e.g. root@192.168.1.10}"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "deploying $SRC -> $HOST:/opt/synicch/src"

tar czf - -C "$SRC" synicch requirements.txt README.md \
  | ssh -o BatchMode=yes "$HOST" '
      set -e
      mkdir -p /opt/synicch/src
      tar xzf - -C /opt/synicch/src
      find /opt/synicch/src -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true

      cat > /usr/local/bin/synicch <<"WRAP"
#!/bin/sh
# Synicch CLI wrapper. Source lives in /opt/synicch/src, venv in /opt/synicch/venv.
PYTHONPATH=/opt/synicch/src exec /opt/synicch/venv/bin/python -m synicch.cli "$@"
WRAP
      chmod 755 /usr/local/bin/synicch
      echo "deployed"
    '

echo "verifying"
ssh -o BatchMode=yes "$HOST" 'synicch --help >/dev/null && echo "  CLI responds"'
