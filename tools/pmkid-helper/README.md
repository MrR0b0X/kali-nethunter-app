# NetHunter PMKID helper

This package builds the separate `hcxdumptool-nethunter-pmkid` executable from
hcxdumptool 8.0.0, upstream commit
`dc0620ba6d4f71e982dae4b53c4a170dd2ecd7ca`. The upstream source and MIT license
are included in `upstream/`; `nethunter-pmkid.patch` contains the integration
changes. The exact version marker is **`8.0.0+nethunter-pmkid-1`**.

The helper requires these capture arguments:

```
--nethunter-target=<unicast BSSID>
--nethunter-client=<dedicated locally administered unicast MAC>
-i <prepared monitor interface>
-f <one integer MHz value>
--bpf=<compiled filter file>
-w <new pcapng path>
```

Target/client must differ, and `-w` must occur once with a nonempty output path.
MACs accept compact hex or colon notation. A missing,
repeated, malformed or nonlocal client identity is rejected before any capture
sockets open. The app prepares the interface UP in monitor mode at the supplied
frequency and owns its cleanup. The helper validates that state; it issues **no
MAC, mode, link, power-saving or frequency changes**.

Only authentication requests (open system, transaction 1), association requests,
STA null frames and ACKs directed to the selected AP can reach the TX socket.
Authentication/association/null frames must use the dedicated client source and
selected BSSID. ACKs contain only the selected AP receiver address. Every TX
call shares the synchronous allowlist, which stays closed until mandatory BPF
attachment succeeds. Attachment failure exits before opening TX.

The independent RX gate admits selected-AP beacons/probe responses and
authentication/association responses or EAPOL M1 addressed to the dedicated
client. Other clients, EAPOL-Start and rogue/disruptive modes cannot reach the
upstream packet handlers. Former broadcast-source association requests use the
dedicated client. The AP workflow can authenticate and associate without an
existing client, then receive a PMKID in the AP's M1 if the AP supplies one.

Standalone BPF compilation requires no capture arguments and changes no radio
state:

```
hcxdumptool-nethunter-pmkid --bpfc='wlan addr2 001122334455' --bpfd=0 > target.bpf
```

Supported capture options include `--tot=<whole minutes>`,
`--associationmax=<attempts>`, `--exitoneapol=1`, `--rds=4`, `--rdt`,
`--errormax` and `--watchdogmax`. The helper always exits on AP PMKID and forces
disassociation/probe-response/rogue-client behavior off, regardless of those
legacy control arguments. Runtime setup/policy failures return nonzero. A zero
exit status is not a PMKID success guarantee; the app validates selected-BSSID
`WPA*01` output after conversion with `hcxpcapngtool`/`hcxhashtool`.

## Build and install

Kali build dependencies: C compiler, Linux/glibc development headers, `patch`,
`sha256sum` and libpcap development files (`build-essential`, `libpcap-dev`).
No script installs packages or starts capture.

```
sh build.sh
sudo sh install.sh
```

Installation defaults to `/usr/local/bin/hcxdumptool-nethunter-pmkid` and leaves
stock hcxdumptool unchanged. `PREFIX` and `DESTDIR` support staged installation.
The build is offline, checks the vendored source hashes, applies the exact patch
with no fuzzy matching, and fixes the version/year. Partial initialization
cleans up safely. The startup RX drain stops after at most ten reads or upon
cancellation, end of input, or an empty receive queue.

Use an app-owned seconds timer with TERM and a grace period before KILL, plus
`--tot` as a fallback. TERM fsyncs the pcapng file; KILL bypasses process cleanup.
The app restores its original interface state in either case.
