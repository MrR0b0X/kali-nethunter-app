package com.offsec.nethunter.wifi;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Requires a fail-closed helper; stock hcxdumptool may continue after a failed RX filter. */
public final class PmkidCapturePlan {
    public static final String HELPER = "/usr/local/bin/hcxdumptool-nethunter-pmkid";
    public static final String VERSION_MARKER = "nethunter-pmkid-1";
    private static final Pattern CHANNEL = Pattern.compile("\\bchannel\\s+(\\d+)\\s+\\((\\d+) MHz\\)");
    public final String iface, bssid, client, capture, filter;
    public final int frequency, seconds;

    public PmkidCapturePlan(WifiSessionRequest request, String iface, String capture, String filter, int frequency) {
        this(request, iface, capture, filter, frequency, randomClient(request.bssid));
    }

    PmkidCapturePlan(WifiSessionRequest request, String iface, String capture, String filter,
                     int frequency, String client) {
        if (request.mode != WifiSessionRequest.Mode.PMKID_CAPTURE) {
            throw new IllegalArgumentException("This operation is not a clientless PMKID capture");
        }
        this.iface = WifiCommands.iface(iface);
        this.bssid = WifiCommands.mac(request.bssid);
        this.client = WifiCommands.mac(client);
        int first = Integer.parseInt(this.client.substring(0, 2), 16);
        if ((first & 3) != 2 || this.client.equals(this.bssid)) {
            throw new IllegalArgumentException("PMKID capture needs a dedicated local client address");
        }
        if (frequency < 2400 || frequency > 7125) throw new IllegalArgumentException("Unsupported Wi-Fi frequency");
        // Validate shell arguments at construction, before any adapter is armed.
        WifiCommands.quote(capture);
        WifiCommands.quote(filter);
        if (capture.isEmpty() || filter.isEmpty() || capture.equals(filter)) {
            throw new IllegalArgumentException("Select distinct PMKID capture and filter files");
        }
        this.capture = capture;
        this.filter = filter;
        this.frequency = frequency;
        this.seconds = request.seconds;
    }

    public static void checkTools(ChrootRunner runner) throws IOException {
        ChrootRunner.Result version = runner.run("test -x " + WifiCommands.quote(HELPER)
                + " && " + WifiCommands.quote(HELPER) + " --version", 5);
        if (version.code != 0 || !Pattern.compile("\\b" + VERSION_MARKER + "\\b").matcher(version.output).find()) {
            throw new IOException("Install the NetHunter PMKID helper in Kali before starting PMKID capture.");
        }
        runner.require("command -v hcxpcapngtool >/dev/null || { echo 'Install hcxtools to verify PMKIDs'; exit 1; }", 5);
    }

    public static int frequencyFromInfo(String info, int channel) throws IOException {
        Matcher match = CHANNEL.matcher(info);
        if (!match.find() || Integer.parseInt(match.group(1)) != channel) {
            throw new IOException("Cannot verify the selected access point channel before PMKID capture");
        }
        int frequency = Integer.parseInt(match.group(2));
        if (frequency < 2400 || frequency > 7125) throw new IOException("Unsupported PMKID capture frequency");
        return frequency;
    }

    public String compileFilterCommand() {
        String expression = "wlan addr2 " + bssid + " and (wlan addr1 ff:ff:ff:ff:ff:ff or wlan addr1 " + client + ")";
        return WifiCommands.quote(HELPER) + " --bpfc=" + WifiCommands.quote(expression)
                + " --bpfd=0 > " + WifiCommands.quote(filter)
                + " && test -s " + WifiCommands.quote(filter);
    }

    public String captureCommand() {
        return WifiCommands.pmkidCapture(iface, capture, filter, bssid, client, frequency, seconds);
    }

    private static String randomClient(String bssid) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[6];
        String address;
        do {
            random.nextBytes(bytes);
            bytes[0] = (byte) ((bytes[0] & 0xfc) | 2);
            address = String.format(Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
                    bytes[0] & 255, bytes[1] & 255, bytes[2] & 255, bytes[3] & 255, bytes[4] & 255, bytes[5] & 255);
        } while (address.equalsIgnoreCase(bssid));
        return address;
    }
}
