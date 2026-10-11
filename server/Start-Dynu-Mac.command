#!/bin/bash
set -u
cd -- "$(dirname -- "$0")" || exit 1
umask 077
python_path="$PWD/.runtime/python/bin/python3"
if [ ! -x "$python_path" ]; then
    echo "Open Start-Mac.command once to prepare Python, then reopen this file."
    read -r -p "Press Return to close..." ignored
    exit 1
fi
"$python_path" dynu_joining.py --root "$PWD" status
result=$?
if [ "$result" -eq 0 ]; then
    "$python_path" dynu_joining.py --root "$PWD" update --watch
    result=$?
else
    echo "Read DYNU_JOINING.md to reserve a hostname and save your private Dynu API key first."
fi
read -r -p "Press Return to close..." ignored
exit "$result"
