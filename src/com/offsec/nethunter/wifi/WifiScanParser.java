package com.offsec.nethunter.wifi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Reads airodump's AP and station tables, including its unquoted commas in ESSIDs. */
public final class WifiScanParser {
    private WifiScanParser() { }

    public static List<WifiNetwork> parse(String csv) {
        Map<String, String[]> aps = new LinkedHashMap<>();
        Map<String, String> stationAps = new LinkedHashMap<>();
        boolean stations = false;
        for (String line : csv.split("\\r?\\n")) {
            if (line.trim().startsWith("Station MAC,")) {
                stations = true;
                continue;
            }
            if (line.trim().startsWith("BSSID,")) {
                stations = false;
                continue;
            }
            String[] fields = line.split(",", -1);
            try {
                String address = WifiCommands.mac(fields[0]);
                if (!stations && fields.length >= 15) {
                    WifiCommands.channel(Integer.parseInt(fields[3].trim()));
                    aps.put(address, fields);
                } else if (stations && fields.length >= 6) {
                    String ap = WifiCommands.mac(fields[5]);
                    // A station can roam; its latest explicit association wins.
                    stationAps.put(address, ap);
                }
            } catch (IllegalArgumentException ignored) {
                // Headers, partial lines during a write, and unassociated stations.
            }
        }
        Map<String, List<String>> clients = groupClients(stationAps);
        List<WifiNetwork> result = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : aps.entrySet()) {
            String[] f = entry.getValue();
            try {
                StringBuilder ssid = new StringBuilder(f[13]);
                for (int i = 14; i < f.length - 1; i++) ssid.append(',').append(f[i]);
                String name = ssid.toString();
                // airodump adds one separator space; retain intentional SSID spaces.
                if (name.startsWith(" ")) name = name.substring(1);
                int nameBytes = Integer.parseInt(f[12].trim());
                if (nameBytes < 0 || nameBytes > 32) continue;
                if (name.endsWith(" ") && name.getBytes(StandardCharsets.UTF_8).length > nameBytes) {
                    name = name.substring(0, name.length() - 1);
                }
                if (nameBytes == 0 || name.startsWith("<length:")) name = "";
                List<String> stationsForAp = clients.get(entry.getKey());
                result.add(new WifiNetwork(entry.getKey(), name, Integer.parseInt(f[3].trim()),
                        Integer.parseInt(f[8].trim()),
                        (f[5].trim() + " " + f[6].trim() + " " + f[7].trim()).trim(),
                        Long.parseLong(f[9].trim()), stationsForAp == null
                        ? Collections.emptyList() : stationsForAp));
            } catch (IllegalArgumentException ignored) {
                // A torn/invalid row must not prevent the other networks from displaying.
            }
        }
        sort(result);
        return result;
    }

    /** Retains observations across partial CSV writes and narrower targeted captures. */
    public static List<WifiNetwork> merge(List<WifiNetwork> previous, List<WifiNetwork> latest) {
        Map<String, WifiNetwork> aps = new LinkedHashMap<>();
        Map<String, String> stationAps = new LinkedHashMap<>();
        for (List<WifiNetwork> snapshot : Arrays.asList(previous, latest)) {
            for (WifiNetwork network : snapshot) {
                aps.put(network.bssid, network);
                for (String client : network.clients) {
                    stationAps.put(WifiCommands.mac(client), network.bssid);
                }
            }
        }
        Map<String, List<String>> clients = groupClients(stationAps);
        List<WifiNetwork> result = new ArrayList<>();
        for (WifiNetwork network : aps.values()) {
            List<String> stationsForAp = clients.get(network.bssid);
            result.add(new WifiNetwork(network.bssid, network.ssid, network.channel, network.signal,
                    network.security, network.beacons, stationsForAp == null
                    ? Collections.emptyList() : stationsForAp));
        }
        sort(result);
        return result;
    }

    private static Map<String, List<String>> groupClients(Map<String, String> stationAps) {
        Map<String, List<String>> clients = new LinkedHashMap<>();
        for (Map.Entry<String, String> station : stationAps.entrySet()) {
            if (!clients.containsKey(station.getValue())) clients.put(station.getValue(), new ArrayList<>());
            clients.get(station.getValue()).add(station.getKey());
        }
        return clients;
    }

    private static void sort(List<WifiNetwork> networks) {
        networks.sort(Comparator.comparingInt((WifiNetwork n) -> n.signal == -1 ? -200 : n.signal)
                .reversed().thenComparing(n -> n.bssid));
    }
}
