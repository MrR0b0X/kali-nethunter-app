# DeAuth module

Open **DeAuth** directly below **EvilTwin** in the NetHunter drawer to access **Networks**, **Captures**, **Uploads**, and **Logs**. The module provides network and client discovery, targeted deauthentication, handshake and PMKID capture, a private capture library, and OnlineHashCrack submissions.

## Using the module

1. For wireless sessions, mount the Kali chroot and connect a supported adapter. **Install Wi-Fi tools** in the overflow menu opens NetHunter's terminal to install `iw`, `aircrack-ng`, `hcxtools`, `procps`, `util-linux`, and `coreutils`.
2. In **Networks**, choose **Refresh adapters**, select an interface, then **Scan networks**. A scan lasts 60 seconds and lists BSSID, SSID, channel, signal, security, observed clients, and beacon counts. **Stop** sits beside **Scan networks**; Scan is disabled during a radio session, and Stop is enabled only while that session can be cancelled.
3. Select a network to open its target panel. Choose **Capture handshake**, **Capture PMKID**, or **Deauth**, then set the channel and duration (5–600 seconds). Handshake/deauth allow a selected client; PMKID uses a dedicated temporary client address and hides client selection and deauth assistance. The selected target and Start button stay visible in the footer. Options survive rotation. Both capture modes default to 120 seconds; deauthentication defaults to 30 seconds. Handshake capture defaults to passive listening, with deauth assistance as an explicit option.
4. Starting capture or deauth opens a dedicated activity with **Progress** and **Logs** tabs. It shows the selected network/client, adapter/channel, current stage, elapsed time, and configured time limit. Its own **Stop** stays visible on either tab. **Keep running** returns to DeAuth; **View session** and the notification reopen the same session. Navigation and rotation do not start or stop radio work. A verified capture can finish early; completed sessions show the result and a **View captures** action. The completed **View session** card disappears when opened or when its result is viewed. Running sessions keep their shortcut, and viewing an older session cannot dismiss a newer session’s card.
5. Use **Captures** to import files or tap one to open its detail panel. The panel shows verification status, size/date, expandable verification details, and icon buttons for validate, share, rename, and delete. **Submit to OnlineHashCrack** stays in the footer and opens a file/account confirmation. Capture imports and uploads work independently of a mounted chroot. Local validation needs the chroot and `hcxpcapngtool`.
6. The module's **Logs** tab shows output as it arrives, including the last session's result. **Follow output** scrolls to new lines; scrolling upward pauses following so earlier output stays readable.

Handshake capture supports WPA/WPA2 personal networks. Active PMKID collection supports WPA2 PSK and WPA2/WPA3 transition networks, using authentication/association with the selected AP without waiting for an existing client. An AP may decline to provide a usable PMKID. SAE-only and enterprise networks are not supported by this mode. Monitor-mode support does not establish injection support. Protected management frames may prevent deauthentication, and tool-reported transmission is not reported as proof that a client disconnected.

Active PMKID collection requires the separate [NetHunter PMKID helper](../tools/pmkid-helper/README.md) and `hcxpcapngtool`. Build and installation instructions are below. The helper installs as `/usr/local/bin/hcxdumptool-nethunter-pmkid`, leaving the stock tool available. It refuses capture if target filtering cannot be attached, restricts transmitted frames to the selected AP and its dedicated local client address, and uses the monitor interface/channel prepared by the app. It does not change interface MAC, mode, link state, power saving, or frequency. The app owns cancellation, time limits, restoration, and PCAPNG archival. Early success requires a verified PMKID for the selected AP and temporary client; a handshake alone does not satisfy a PMKID session.

Client counts show unique station MACs observed for each BSSID, rather than the router's total connected devices. General scans hop across 2.4 GHz and 5 GHz channels, so quiet devices and traffic on other channels may be missed. Select a network, confirm its channel, and choose **Discover clients (60s)** to listen passively on that channel for a full minute. This action keeps the Networks screen and its Stop control available, refreshes the selected AP’s client observations, preserves other networks, and works without packet injection or capture-validation tools. Select the network again afterward to choose a discovered client. Observations accumulate within each scan, including across incomplete CSV rewrites; targeted captures update scan results without discarding other networks. A newly observed association moves a roaming station to its latest BSSID. Starting a new network scan clears all previous observations; client discovery clears only the selected AP’s previous client list so old observations cannot masquerade as new results.

## Capture storage and validation

Imports accept CAP, PCAP, and PCAPNG contents, up to **100,000,000 bytes per file**. They are copied atomically into private app storage with UUID filenames and SHA-256 digests. Renaming changes the display name and preserves queued uploads. Document permissions acquired for an import are released after use; pre-existing grants are preserved. Sharing uses a read-only FileProvider URI restricted to the capture directory.

| Location | Purpose |
| --- | --- |
| `NhPaths.APP_SD_FILES_PATH/wifi/sessions/<uuid>/` | Temporary radio output, capture snapshots, and recovery metadata. Android/Kali visibility is checked before use. |
| `Context.getFilesDir()/wifi/captures/` | Private, archived capture originals. |
| `Context.getFilesDir()/wifi/scripts/` | Monitor-mode helpers prepared from the current APK, independently of legacy asset extraction. |
| `wifi_captures.db` | Capture metadata and persisted upload records. |
| `wifi_radio_recovery` preferences | Original adapter identity, mode, channel, link state, internal Wi-Fi settings, and chroot. |

`hcxpcapngtool` extracts Hashcat 22000 records from a bounded snapshot. The validator checks complete records for the selected AP/client. A banner from `airodump-ng` or unrelated EAPOL messages in the same file cannot mark the target as verified. Explicitly validating a library file inspects all its networks.

The library distinguishes **Handshake verified**, **PMKID verified**, both, **No usable WPA material found**, and **Not verified**. Missing tools, interruptions, and validation failures remain unverified. Original captures are preserved. Files with pending uploads cannot be deleted until those uploads have stopped.

## OnlineHashCrack submissions

In **Uploads**, save the email of an existing OnlineHashCrack account. From a capture's actions, choose **Submit to OnlineHashCrack** and confirm the file and account. A completed wireless capture does not create an upload automatically.

The implementation follows the [public WPA API](https://www.onlinehashcrack.com/how-developpers-public-api-documentation.php): HTTPS multipart POST to `https://api.onlinehashcrack.com` with `email` and `file`. It sends the capture original; no API key or local raw-hash conversion is required. Android performs the request with normal TLS verification and a four-minute deadline.

| Outcome | Meaning |
| --- | --- |
| Accepted for processing | The API acknowledged accepted material. Password recovery is a separate result. |
| Already submitted | All reported skipped material has the API's `already_sent` reason. |
| Partially accepted | Some material was accepted/already submitted and other material was rejected or unclassified. Counts and reasons are retained. |
| Rejected | No usable material was accepted, including a successful HTTP response with no hashes. |
| Upload failed | A definite local, account, or request error. The message explains the failure. |
| Outcome unknown | The response was unrecognized or transmission ended without acknowledgment. Check the dashboard before explicitly retrying. |
| Cancelled | Local work was cancelled. Data already received by the API cannot be recalled. |

Jobs persist through WorkManager, wait for connectivity, show progress, and retry transient failures with backoff, up to four attempts per job. Account email and file digest are captured when queued. Missing/changed local files fail without network retries. Accepted or queued duplicates are identified by account plus contents, including renamed or re-imported copies. Unknown outcomes do not trigger automatic resubmission. Retry creates another upload record; submitting under a corrected account is available from the capture actions.

Use **Open OnlineHashCrack dashboard** for cracking results. Public-API submission does not synchronize recovered passwords into NetHunter; authenticated v2 result synchronization remains a separate feature.

## Session cleanup and compatibility

The connected-device foreground service owns a serial radio session. Each tool runs in an owned process group with an independent deadline and an ownership token. Stop and cleanup target those groups. Radio state is recorded before mutation and verified after restoration, including adapters already in monitor mode.

The session activity observes immutable request metadata and the service's session identity. A screen restored for an old session cannot cancel a newer one. If the app process has ended, reopening that screen reports the session as unavailable and offers navigation back to DeAuth for capture review/recovery. It never silently restarts the operation.

After an interrupted process, use **Restore interrupted session** with the original adapter/chroot available. Starting a new session also attempts recovery first. Recovery stops owned processes before restoring the radio, then archives readable orphaned captures as unverified. It avoids duplicate archival after interrupted cleanup. Unreadable, oversized, or otherwise unarchivable output stays in its session directory and is reported for inspection. Recovery does not restart scanning or transmission.

NetHunter launch guards cover EvilTwin, WPS, WiFiPumpkin, MAC changes, custom commands, Kali services, and chroot mutations. EvilTwin/WPS background scans and chroot operations reserve their work before dispatch, so preparation cannot race a new Wi-Fi session. Existing wireless processes and active interfaces on the same physical radio are also checked. Commands launched independently in a terminal cannot be completely coordinated; finish those operations before using this module.

Internal `wlan0` can use NetHunter's existing QCACLD/Nexmon monitor scripts when detected. Other interfaces use `iw`; internal scripts are never applied to `swlan0` or a USB adapter just because of their name. Failed restoration retains the recovery record for another attempt.

The module prepares both monitor helpers from its packaged assets before changing an internal radio. Missing or outdated private copies are repaired atomically, including during interrupted-session recovery, without depending on the extracted `nh_files/modules` directory. Session failures are recorded in logcat under `WifiSessionService` or `WifiViewModel` as well as shown in the app.

## Architecture

Source paths below are relative to `src/com/offsec/nethunter/`, except resources and the native helper package. Screens use `Theme.Nethunter`, `Tech.Card`, and the existing NetHunter button and tab styles.

| Area | Main sources |
| --- | --- |
| Navigation and themed screens | `WifiFragment.java`, `WifiTargetDialog.java`, `WifiCaptureDialog.java`, `WifiSessionActivity.java`, `WifiLogFragment.java`, `res/layout/wifi*.xml`, `res/values/strings_wifi.xml` |
| Foreground lifecycle | `service/WifiSessionService.java`, `wifi/WifiSessionState.java`, `wifi/WifiSessionEngine.java` |
| Radio/process ownership | `wifi/InterfaceController.java`, `wifi/ChrootRunner.java`, `wifi/WifiSessionGuard.java`, `wifi/PmkidCapturePlan.java`, `tools/pmkid-helper/` |
| Capture data | `wifi/WifiScanParser.java`, `wifi/CaptureValidator.java`, `wifi/CaptureRecovery.java`, `wifi/WifiStore.java` |
| API/jobs | `wifi/OnlineHashCrackClient.java`, `wifi/UploadWorker.java` |

The Android UI and persistent upload worker are independent of the Kali process runner. `WifiSessionService` serializes radio work; `WifiSessionState` exposes snapshots to the module and session activity. `InterfaceController` owns preparation and restoration, while `ChrootRunner` owns bounded probes and tool process groups. `PmkidCapturePlan` checks the helper version, reads the prepared frequency, creates a dedicated client identity, and compiles its target filter before capture starts.

## Build and install

Build the Android application from the repository root:

```sh
./gradlew assembleDebug --console=plain
./gradlew lintDebug --console=plain
```

The APK is generated at `build/outputs/apk/debug/kali-nethunter-app-debug.apk`. WorkManager 2.9.1 supports the project's minimum Android API level of 21.

For active PMKID capture, build the helper inside Kali with `build-essential`, `libpcap-dev`, and `patch` installed. From `tools/pmkid-helper/`, run:

```sh
sh build.sh
sudo sh install.sh
```

The build checks vendored source hashes and applies the exact patch before compiling. Installation requires root and defaults to `/usr/local/bin/hcxdumptool-nethunter-pmkid`; `PREFIX` and `DESTDIR` support staged installation. Building and installing the helper do not start a wireless session. Install `hcxtools` in the same chroot for capture verification.

## Limitations

Adapter, kernel, driver, and firmware support determine whether monitor mode, packet injection, and restoration work. Passive discovery can miss quiet stations. An AP may omit PMKIDs or require protected management frames, and transmission alone does not prove disconnection. Recovery depends on the original adapter and chroot remaining available.

Each radio profile should be checked for Stop, app interruption, adapter unplug/reconnect, channel changes, and restoration. API acceptance depends on the account and capture contents; upload acceptance does not establish password recovery.

Separate capture/injection adapters, persistent exclusions, raw 22000 export, broad multi-network deauthentication, and local cracking are outside this implementation.
