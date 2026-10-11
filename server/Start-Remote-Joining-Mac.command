#!/bin/bash
set -u
cd -- "$(dirname -- "$0")"
python_path="$PWD/.runtime/python/bin/python3"
if [ ! -x "$python_path" ]; then
    echo "Open Start-Mac.command once to prepare the server's Python runtime, then reopen this file."
    read -r -p "Press Return to close..." ignored
    exit 1
fi
"$python_path" remote_joining.py "$@"
result=$?
if [ "$result" -ne 0 ]; then
    echo "Remote setup stopped. Read REMOTE_JOINING.md before trying again."
    read -r -p "Press Return to close..." ignored
fi
exit "$result"
