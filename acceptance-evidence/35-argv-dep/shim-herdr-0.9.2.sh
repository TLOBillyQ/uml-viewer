#!/bin/sh
# Acceptance shim for ticket #35 scenario 2b: a Herdr older than the 0.9.3
# minimum. --version reports 0.9.2; probe-herdr rejects before any api call.
if [ "$1" = "--version" ]; then
  printf 'herdr 0.9.2\n'
  exit 0
fi
printf 'fake herdr 0.9.2: unexpected invocation' >&2
printf '%s ' "$@" >&2
printf '\n' >&2
exit 1
