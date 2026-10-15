#!/bin/sh
# Acceptance shim for ticket #35 scenario 1: records cwd + argv of every
# invocation the wrapper chain makes to "clojure", without starting a JVM.
# Log line format (base64 fields, space separated):
#   <cwd_b64> <argc> <arg1_b64> <arg2_b64> ...
log=${UML_SHIM_LOG:?UML_SHIM_LOG not set}
{
  printf '%s' "$(printf '%s' "$PWD" | base64)"
  printf ' %s' "$#"
  for a in "$@"; do
    printf ' %s' "$(printf '%s' "$a" | base64)"
  done
  printf '\n'
} >> "$log"

# Snapshot any launch manifest visible from our working directory (the
# launcher may delete it before or right after spawning us).
for m in "$PWD"/.uml-viewer/launch-*.json; do
  [ -f "$m" ] && cp "$m" "${UML_MANIFEST_SNAP:?}/" 2>/dev/null
done

case " $* " in
  *" uml-viewer.adapters.companion "*)
    # Preflight: wrapper requires exactly one stdout line "herdr".
    printf 'herdr\n'
    exit 0
    ;;
  *)
    # Viewer launch: never start a real JVM.
    exit 42
    ;;
esac
