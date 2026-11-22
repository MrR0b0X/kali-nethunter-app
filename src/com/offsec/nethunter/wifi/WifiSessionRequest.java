package com.offsec.nethunter.wifi;

import android.content.Intent;

public final class WifiSessionRequest {
    public static final int DEFAULT_SCAN_SECONDS = 60;
    public enum Mode { SCAN, DEAUTH, CAPTURE, CLIENT_SCAN, PMKID_CAPTURE }
    public final Mode mode;
    public final String iface, bssid, client, ssid;
    public final int channel, seconds;
    public final boolean assist;

    public WifiSessionRequest(Mode mode, String iface, String bssid, String client, String ssid,
                              int channel, int seconds, boolean assist) {
        if (mode == null) throw new IllegalArgumentException("Select a Wi-Fi operation");
        this.mode = mode;
        this.iface = WifiCommands.iface(iface);
        this.bssid = mode == Mode.SCAN ? "" : WifiCommands.mac(bssid);
        this.client = client == null || client.isEmpty() ? "" : WifiCommands.mac(client);
        if (mode == Mode.PMKID_CAPTURE) {
            if (!this.client.isEmpty() || assist) {
                throw new IllegalArgumentException("Clientless PMKID capture does not target a client or use deauth assistance");
            }
            if ((Integer.parseInt(this.bssid.substring(0, 2), 16) & 1) != 0) {
                throw new IllegalArgumentException("Select a unicast access point address");
            }
        }
        this.ssid = ssid == null ? "" : ssid;
        this.channel = mode == Mode.SCAN ? 0 : WifiCommands.channel(channel);
        this.seconds = WifiCommands.duration(seconds);
        this.assist = mode == Mode.CAPTURE && assist;
    }

    public Intent write(Intent intent) {
        return intent.putExtra("mode", mode.name()).putExtra("iface", iface).putExtra("bssid", bssid)
                .putExtra("client", client).putExtra("ssid", ssid).putExtra("channel", channel)
                .putExtra("seconds", seconds).putExtra("assist", assist);
    }

    public static WifiSessionRequest read(Intent intent) {
        return new WifiSessionRequest(Mode.valueOf(intent.getStringExtra("mode")), intent.getStringExtra("iface"),
                intent.getStringExtra("bssid"), intent.getStringExtra("client"), intent.getStringExtra("ssid"),
                intent.getIntExtra("channel", 0), intent.getIntExtra("seconds", 0), intent.getBooleanExtra("assist", false));
    }

    public boolean hasSessionScreen() {
        return mode == Mode.CAPTURE || mode == Mode.DEAUTH || mode == Mode.PMKID_CAPTURE;
    }
}
