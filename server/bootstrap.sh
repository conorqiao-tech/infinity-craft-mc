#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
case "$(uname -s)" in Darwin) system=darwin;; Linux) system=linux;; *) echo "Unsupported system. See README.md."; exit 1;; esac
case "$(uname -m)" in arm64|aarch64) arch=arm64;; x86_64|amd64) arch=x64;; *) echo "Unsupported CPU. See README.md."; exit 1;; esac
python_path="$PWD/.runtime/python/bin/python3"
if [ ! -x "$python_path" ]; then
    mkdir -p .runtime
    if ! mkdir .runtime/bootstrap.lock 2>/dev/null; then
        echo "Setup is already open. If it crashed, close its Terminal window and remove .runtime/bootstrap.lock."
        exit 1
    fi
    stage="$(mktemp -d "$PWD/.runtime/python-stage.XXXXXX")"
    trap 'rm -rf "$stage"; rmdir .runtime/bootstrap.lock' EXIT HUP INT TERM
    pin="setup/$system-$arch.txt"
    url="$(sed -n '1p' "$pin")"
    expected="$(sed -n '2p' "$pin")"
    echo "Infinity Armor: preparing the launcher (first time only)..."
    curl --fail --location --proto '=https' --proto-redir '=https' --retry 2 --connect-timeout 30 --max-time 900 --progress-bar "$url" --output "$stage/python.tar.gz"
    if command -v shasum >/dev/null 2>&1; then
        actual="$(shasum -a 256 "$stage/python.tar.gz" | cut -d ' ' -f 1)"
    else
        actual="$(sha256sum "$stage/python.tar.gz" | cut -d ' ' -f 1)"
    fi
    [ "$actual" = "$expected" ] || { echo "Download verification failed. Open the launcher to retry."; exit 1; }
    tar -xzf "$stage/python.tar.gz" -C "$stage"
    "$stage/python/bin/python3" -c 'import ssl, http.server, pathlib' || exit 1
    if [ -e .runtime/python ]; then echo "Incomplete runtime exists. Remove .runtime/python and retry."; exit 1; fi
    mv "$stage/python" .runtime/python
    rm -rf "$stage"
    rmdir .runtime/bootstrap.lock
    trap - EXIT HUP INT TERM
fi
exec "$python_path" server.py --dashboard "$@"
