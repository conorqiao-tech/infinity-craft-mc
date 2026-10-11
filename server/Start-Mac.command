#!/bin/bash
cd -- "$(dirname -- "$0")"
bash bootstrap.sh "$@"
result=$?
if [ "$result" -ne 0 ]; then
    echo "Setup stopped. Read the message above, then open this file to try again."
    read -r -p "Press Return to close..." ignored
fi
exit "$result"
