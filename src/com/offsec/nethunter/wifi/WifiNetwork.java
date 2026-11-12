package com.offsec.nethunter.wifi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One BSSID, rather than one SSID: distinct access points must stay distinct. */
public final class WifiNetwork {
    public final String bssid;
    public final String ssid;
    public final int channel;
    public final int signal;
    public final String security;
    public final long beacons;
    public final List<String> clients;

    public WifiNetwork(String bssid, String ssid, int channel, int signal, String security,
                       long beacons, List<String> clients) {
        this.bssid = WifiCommands.mac(bssid);
        this.ssid = ssid;
        this.channel = channel;
        this.signal = signal;
        this.security = security;
        this.beacons = beacons;
        this.clients = Collections.unmodifiableList(new ArrayList<>(clients));
    }

    public boolean supportsWpaCapture() {
        String modes = security.toUpperCase(Locale.ROOT);
        // Transition networks advertise both SAE and PSK; the PSK exchange is usable.
        return modes.contains("WPA") && modes.contains("PSK");
    }

    public boolean supportsPmkidCapture() {
        String modes = security.toUpperCase(Locale.ROOT);
        return (modes.contains("WPA2") || modes.contains("WPA3")) && modes.contains("PSK");
    }
}
