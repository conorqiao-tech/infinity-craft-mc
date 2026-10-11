#!/bin/bash
cd -- "$(dirname -- "$0")"
python_path="$PWD/.runtime/python/bin/python3"
[ -x "$python_path" ] || { echo "The prepared Python runtime is missing."; exit 1; }
"$python_path" pinggy_joining.py stop
result=$?
read -r -p "Press Return to close..." ignored
exit "$result"
