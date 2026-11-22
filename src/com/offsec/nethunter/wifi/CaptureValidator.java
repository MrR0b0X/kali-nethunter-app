package com.offsec.nethunter.wifi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** hcxpcapngtool pairs frames; isolated message types from different clients are not proof. */
public final class CaptureValidator {
    private final ChrootRunner runner;
    private final WifiPaths paths;

    public CaptureValidator(ChrootRunner runner, WifiPaths paths) { this.runner = runner; this.paths = paths; }

    public Verification verify(File capture, String bssid, String client) throws IOException {
        return verify(capture, bssid, client, false);
    }

    public Verification verifyPmkid(File capture, String bssid, String client) throws IOException {
        return verify(capture, bssid, client, true);
    }

    private Verification verify(File capture, String bssid, String client, boolean pmkidOnly) throws IOException {
        String id = "verify-" + UUID.randomUUID();
        File snapshot = new File(capture.getParentFile(), id + ".cap");
        File hashes = new File(capture.getParentFile(), id + ".22000");
        try {
            if (runner.run("command -v hcxpcapngtool >/dev/null", 5).code != 0) {
                return new Verification("UNVERIFIED", "Install hcxtools to verify this capture", 0);
            }
            WifiPaths.copy(capture, snapshot);
            ChrootRunner.Result result = runner.run("hcxpcapngtool -o " + WifiCommands.quote(paths.guest(hashes))
                    + " " + WifiCommands.quote(paths.guest(snapshot)), 25);
            if (result.code != 0) return new Verification("UNVERIFIED", result.output, 0);
            String records = hashes.isFile() ? WifiPaths.read(hashes, 2 * 1024 * 1024) : "";
            return pmkidOnly ? parsePmkid(records, bssid, client) : parse(records, bssid, client);
        } finally {
            snapshot.delete();
            hashes.delete();
        }
    }

    public static Verification parsePmkid(String hashes, String targetBssid, String targetClient) {
        StringBuilder records = new StringBuilder();
        for (String line : hashes.split("\\r?\\n")) {
            if (line.trim().startsWith("WPA*01*")) records.append(line).append('\n');
        }
        return parse(records.toString(), targetBssid, targetClient);
    }

    public static boolean hasPmkid(String hashes, String targetBssid, String targetClient) {
        return parsePmkid(hashes, targetBssid, targetClient).hasPmkid();
    }

    public static Verification parse(String hashes, String targetBssid, String targetClient) {
        String target = targetBssid == null || targetBssid.isEmpty() ? ""
                : WifiCommands.mac(targetBssid).replace(":", "");
        String station = targetClient == null || targetClient.isEmpty() ? ""
                : WifiCommands.mac(targetClient).replace(":", "");
        boolean eapol = false, pmkid = false;
        Set<String> networks = new LinkedHashSet<>();
        int count = 0;
        for (String line : hashes.split("\\r?\\n")) {
            String[] p = line.trim().split("\\*", -1);
            if (p.length != 9 || !p[0].equals("WPA") || !p[2].matches("(?i)[0-9a-f]{32}")
                    || p[2].matches("0{32}")
                    || !p[3].matches("(?i)[0-9a-f]{12}") || !p[4].matches("(?i)[0-9a-f]{12}")
                    || !p[5].matches("(?i)([0-9a-f]{2}){1,32}")) continue;
            if (!target.isEmpty() && !target.equalsIgnoreCase(p[3])) continue;
            if (!station.isEmpty() && !station.equalsIgnoreCase(p[4])) continue;
            if (p[1].equals("01") && p[6].isEmpty() && p[7].isEmpty() && p[8].isEmpty()) pmkid = true;
            else if (p[1].equals("02") && p.length >= 9 && p[6].matches("(?i)[0-9a-f]{64}")
                    && p[7].matches("(?i)([0-9a-f]{2}){99,256}") && p[8].matches("(?i)[0-9a-f]{2}")) eapol = true;
            else continue;
            count++;
            StringBuilder mac = new StringBuilder();
            for (int i = 0; i < 12; i += 2) {
                if (i > 0) mac.append(':');
                mac.append(p[3], i, i + 2);
            }
            byte[] ssid = new byte[p[5].length() / 2];
            for (int i = 0; i < ssid.length; i++) ssid[i] = (byte) Integer.parseInt(p[5].substring(i * 2, i * 2 + 2), 16);
            if (networks.size() < 40) networks.add(mac.toString().toUpperCase(Locale.ROOT) + " · "
                    + new String(ssid, StandardCharsets.UTF_8).replaceAll("\\p{Cntrl}", "_"));
        }
        String state = eapol ? (pmkid ? "HANDSHAKE_AND_PMKID" : "HANDSHAKE") : (pmkid ? "PMKID" : "EMPTY");
        return new Verification(state, String.join("\n", networks), count);
    }

    public static final class Verification {
        public final String state, details;
        public final int hashes;
        Verification(String state, String details, int hashes) {
            this.state = state;
            this.details = details == null ? "" : details;
            this.hashes = hashes;
        }
        public boolean usable() { return hashes > 0; }
        public boolean hasPmkid() { return state.equals("PMKID") || state.equals("HANDSHAKE_AND_PMKID"); }
    }
}
