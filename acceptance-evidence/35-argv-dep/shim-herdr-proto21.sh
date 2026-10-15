#!/bin/sh
# Acceptance shim for ticket #35 scenario 2c: version passes (0.9.3) but the
# bundled api schema is not protocol 22.
if [ "$1" = "--version" ]; then
  printf 'herdr 0.9.3\n'
  exit 0
fi
if [ "$1" = "api" ] && [ "$2" = "schema" ]; then
  printf '{"$schema":"https://herdr.dev/schema.json","protocol":21,"commands":[]}\n'
  exit 0
fi
printf 'fake herdr proto21: unexpected invocation' >&2
printf '%s ' "$@" >&2
printf '\n' >&2
exit 1
