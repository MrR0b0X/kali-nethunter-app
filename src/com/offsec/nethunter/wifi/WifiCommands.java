package com.offsec.nethunter.wifi;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Arguments from scans and documents are untrusted, even on a rooted device. */
public final class WifiCommands {
    private static final Pattern MAC = Pattern.compile("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}");
    private static final Pattern CHANNEL_CHANGE = Pattern.compile(
            "(?i)(?:AP uses channel|AP is on channel|but.*?channel)\\s+(\\d{1,3})");

    private WifiCommands() { }

    public static String quote(String value) {
        if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid argument");
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static String iface(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,14}")) {
            throw new IllegalArgumentException("Invalid wireless interface");
        }
        return value;
    }

    public static String mac(String value) {
        if (value == null || !MAC.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Invalid MAC address");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public static int channel(int channel) {
        if (channel < 1 || channel > 233) throw new IllegalArgumentException("Invalid Wi-Fi channel");
        return channel;
    }

    public static int duration(int seconds) {
        if (seconds < 5 || seconds > 600) throw new IllegalArgumentException("Duration must be 5–600 seconds");
        return seconds;
    }

    public static String scan(String iface, String prefix) {
        return "exec airodump-ng --band abg --write-interval 1 --output-format csv -w "
                + quote(prefix) + " " + quote(iface(iface));
    }

    public static String capture(String iface, String prefix, String bssid, int channel) {
        return "exec airodump-ng --write-interval 1 --output-format pcap,csv --bssid "
                + quote(mac(bssid)) + " --channel " + channel(channel) + " -w "
                + quote(prefix) + " " + quote(iface(iface));
    }

    public static String clientScan(String iface, String prefix, String bssid, int channel) {
        return "exec airodump-ng --write-interval 1 --output-format csv --bssid "
                + quote(mac(bssid)) + " --channel " + channel(channel) + " -w "
                + quote(prefix) + " " + quote(iface(iface));
    }

    public static String pmkidCapture(String iface, String capture, String filter, String bssid,
                                      String client, int frequency, int seconds) {
        duration(seconds);
        if (frequency < 2400 || frequency > 7125) throw new IllegalArgumentException("Unsupported Wi-Fi frequency");
        int minutes = (seconds + 59) / 60;
        return "exec " + quote(PmkidCapturePlan.HELPER) + " -i " + quote(iface(iface))
                + " -f " + frequency + " -w " + quote(capture) + " --bpf=" + quote(filter)
                + " --nethunter-target=" + quote(mac(bssid)) + " --nethunter-client=" + quote(mac(client))
                + " --disable_disassociation --proberesponsetx=0 --m2max=0 --associationmax=4"
                + " --exitoneapol=1 --tot=" + minutes;
    }

    public static String deauth(String iface, String bssid, String client, int packets) {
        if (packets < 1 || packets > 64) throw new IllegalArgumentException("Invalid burst size");
        return "exec aireplay-ng --deauth " + packets + " -a " + quote(mac(bssid))
                + (client == null || client.isEmpty() ? "" : " -c " + quote(mac(client)))
                + " " + quote(iface(iface));
    }

    public static int reportedChannel(String output) {
        Matcher matcher = CHANNEL_CHANGE.matcher(output);
        if (!matcher.find()) return 0;
        int result = Integer.parseInt(matcher.group(1));
        return result >= 1 && result <= 233 ? result : 0;
    }
}
