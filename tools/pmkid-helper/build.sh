#!/bin/sh
# Build only; never installs dependencies, touches interfaces or starts capture.
set -eu
pmkid_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
pmkid_build_dir="$pmkid_dir/.build"
mkdir -p "$pmkid_build_dir/include"
(cd "$pmkid_dir/upstream" && sha256sum -c SHA256SUMS >/dev/null)
cp "$pmkid_dir/upstream/hcxdumptool.c" "$pmkid_build_dir/hcxdumptool.c"
cp "$pmkid_dir/upstream/include/"*.h "$pmkid_build_dir/include/"
cp "$pmkid_dir/nethunter_pmkid_policy.h" "$pmkid_build_dir/"
patch --batch --fuzz=0 --silent -d "$pmkid_build_dir" -p1 < "$pmkid_dir/nethunter-pmkid.patch"
${CC:-cc} ${CFLAGS:--O2 -Wall -Wextra} -std=gnu99 \
    -ffile-prefix-map="$pmkid_build_dir"=. \
    '-DVERSION_TAG="8.0.0+nethunter-pmkid-1"' '-DVERSION_YEAR="2026"' \
    -DHCXSTATUSOUT -DHCXWANTLIBPCAP \
    "$pmkid_build_dir/hcxdumptool.c" -o "$pmkid_build_dir/hcxdumptool-nethunter-pmkid" -lpcap
printf '%s\n' "$pmkid_build_dir/hcxdumptool-nethunter-pmkid"
