#!/bin/bash
cd -- "$(dirname -- "$0")"
python_path="$PWD/.runtime/python/bin/python3"
[ -x "$python_path" ] || { echo "Open Start-Mac.command first to prepare Python."; exit 1; }
"$python_path" pinggy_install.py
result=$?
if [ "$result" -eq 0 ]; then
    "$python_path" pinggy_joining.py start
    result=$?
fi
read -r -p "Press Return to close..." ignored
exit "$result"
