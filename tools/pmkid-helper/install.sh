#!/bin/sh
# Run after build.sh inside Kali. Installs only the separately named helper.
set -eu
pmkid_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
pmkid_binary="$pmkid_dir/.build/hcxdumptool-nethunter-pmkid"
pmkid_version=$("$pmkid_binary" --version)
case "$pmkid_version" in
    *8.0.0+nethunter-pmkid-1*) ;;
    *) printf '%s\n' 'Refusing installation: helper version marker missing.' >&2; exit 1 ;;
esac
pmkid_destination="${DESTDIR:-}${PREFIX:-/usr/local}/bin/hcxdumptool-nethunter-pmkid"
install -D -m 0755 "$pmkid_binary" "$pmkid_destination"
printf '%s\n' "$pmkid_destination"
