package com.offsec.nethunter.wifi;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WirelessInterface {
    public final String name, phy, type;
    public final int channel;

    WirelessInterface(String name, String phy, String type, int channel) {
        this.name = WifiCommands.iface(name); this.phy = phy; this.type = type; this.channel = channel;
    }

    @Override public String toString() { return name + " · " + type + " · " + phy; }

    public static List<WirelessInterface> parse(String output) {
        List<WirelessInterface> found = new ArrayList<>();
        String phy = "", name = null, type = "unknown";
        int channel = 0;
        Pattern channelPattern = Pattern.compile("channel (\\d+)");
        for (String raw : (output + "\nphy#end").split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("phy#") || line.startsWith("Interface ")) {
                if (name != null) {
                    try { found.add(new WirelessInterface(name, phy, type, channel)); }
                    catch (IllegalArgumentException ignored) { }
                    name = null;
                }
                if (line.startsWith("phy#")) phy = "phy" + line.substring(4);
                else { name = line.substring(10).trim(); type = "unknown"; channel = 0; }
            } else if (line.startsWith("type ")) type = line.substring(5).trim();
            else {
                Matcher matcher = channelPattern.matcher(line);
                if (matcher.find()) channel = Integer.parseInt(matcher.group(1));
            }
        }
        return found;
    }
}
