package com.offsec.nethunter.wifi;

import android.content.Context;
import android.content.SharedPreferences;

import com.offsec.nethunter.utils.NhPaths;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Records radio state before mutation and verifies restoration before forgetting it. */
public final class InterfaceController {
    private final ChrootRunner runner;
    private final SharedPreferences recovery;
    private final MonitorModeScripts monitorScripts;
    private JSONObject original;
    private String monitorInterface;
    private boolean nexmon;

    public InterfaceController(Context context, ChrootRunner runner) {
        this.runner = runner;
        recovery = context.getSharedPreferences("wifi_radio_recovery", Context.MODE_PRIVATE);
        monitorScripts = new MonitorModeScripts(context);
    }

    public List<WirelessInterface> discover() throws IOException {
        return WirelessInterface.parse(runner.require("iw dev", 10));
    }

    public void checkTools(boolean injection) throws IOException {
        runner.require("for tool in iw ip airodump-ng setsid timeout bash pgrep ps awk "
                + (injection ? "aireplay-ng " : "")
                + "; do command -v \"$tool\" >/dev/null || { printf 'Missing Kali tool: %s\\n' \"$tool\"; exit 1; }; done", 10);
    }

    public void recover() throws IOException {
        String saved = recovery.getString("original", null);
        if (saved != null) {
            try { original = new JSONObject(saved); }
            catch (JSONException e) { throw new IOException("Invalid saved radio state", e); }
            if (!original.optString("chroot", NhPaths.CHROOT_PATH()).equals(NhPaths.CHROOT_PATH())) {
                original = null;
                throw new IOException("Select the original Kali chroot before restoring the interrupted Wi-Fi session");
            }
        }
        // Wait for owned groups to exit before any radio restoration, including after app death.
        try { runner.recoverOwnedProcesses(); }
        catch (IOException e) {
            original = null; // Leave the persisted record; do not restore while processes still run.
            throw e;
        }
        restore();
    }

    public String prepare(String selected, int channel) throws IOException {
        WifiCommands.iface(selected);
        List<WirelessInterface> before = discover();
        WirelessInterface radio = find(before, selected);
        if (radio == null) throw new IOException("Selected wireless adapter is no longer connected");
        if (!radio.type.equals("managed") && !radio.type.equals("monitor")) {
            throw new IOException("Adapter is in use in " + radio.type + " mode");
        }
        for (WirelessInterface other : before) {
            if (!other.name.equals(selected) && other.phy.equals(radio.phy) && isUp(other.name)) {
                throw new IOException("The same physical radio is also in use by " + other.name);
            }
        }
        String conflicts = runner.require("for tool in airodump-ng aireplay-ng mdk3 mdk4 reaver bully hostapd kismet "
                + "hcxdumptool tcpdump; do pgrep -x \"$tool\" >/dev/null && printf '%s\\n' \"$tool\"; done; "
                + "pgrep -af '(^|/)(wifite|wifipumpkin3|oneshot[.]py|module[.]py|start-kismet|hcxdumptool-nethunter-pmkid)([[:space:]]|$)' || true", 10);
        if (!conflicts.isEmpty()) throw new IOException("Stop the other wireless operation first: " + conflicts.replace('\n', ' '));

        String devicePath = hostRequire("readlink -f /sys/class/net/" + WifiCommands.quote(selected) + "/device", 5).trim();
        String address = WifiCommands.mac(hostRequire("cat /sys/class/net/" + WifiCommands.quote(selected) + "/address", 5));
        // Existing NetHunter device scripts operate on wlan0, never on swlan0 or another adapter.
        boolean internal = selected.equals("wlan0") && !devicePath.contains("/usb");
        String conPath = internal ? hostRequire("for p in /sys/module/*/parameters/con_mode; do "
                + "[ -f \"$p\" ] && { printf '%s' \"$p\"; break; }; done; true", 5).trim() : "";
        nexmon = internal && runner.host("strings /vendor/firmware/*bcm* 2>/dev/null | grep -qi nexmon", 10).code == 0;
        String profile = nexmon ? "nexmon" : (!conPath.isEmpty() ? "qcacld" : "generic");
        MonitorModeScripts.Scripts scripts = profile.equals("generic") ? null : monitorScripts.prepare();
        String device = internal ? hostRequire("getprop ro.product.device", 5).trim() : "";
        boolean samsung = nexmon && device.matches("beyond(?:0lte|1lte|2lte|x)");
        int conMode = -1, nexMode = -1;
        String chanspec = "";
        if (profile.equals("qcacld")) {
            if (!conPath.matches("/sys/module/[a-zA-Z0-9_-]+/parameters/con_mode")) throw new IOException("Invalid driver path");
            String mode = hostRequire("cat " + WifiCommands.quote(conPath), 5).trim();
            if (!mode.equals("0") && !mode.equals("4")) throw new IOException("Unsupported current QCACLD mode: " + mode);
            conMode = Integer.parseInt(mode);
        }
        if (nexmon) {
            nexMode = readNexmonMode(samsung);
            if (nexMode < 0 || nexMode > 2) throw new IOException("Unsupported current Nexmon monitor mode");
            chanspec = readChanspec();
        }
        boolean wasMonitor = radio.type.equals("monitor") || conMode == 4 || nexMode > 0;
        String wifi = !internal ? "" : hostRequire("settings get global wifi_on", 5).trim();
        if (internal && !wifi.matches("[012]")) {
            throw new IOException("Cannot determine Android Wi-Fi state before changing the internal adapter");
        }
        String watchWifi = !internal ? "" : hostRequire("settings get system clockwork_wifi_setting", 5).trim();
        try {
            original = new JSONObject().put("name", selected).put("phy", radio.phy).put("type", radio.type)
                    .put("channel", radio.channel).put("wifi", wifi).put("watchWifi", watchWifi)
                    .put("profile", profile).put("wasMonitor", wasMonitor).put("up", isUp(selected))
                    .put("internal", internal)
                    .put("devicePath", devicePath).put("address", address).put("chroot", NhPaths.CHROOT_PATH())
                    .put("conPath", conPath).put("conMode", conMode).put("nexMode", nexMode)
                    .put("chanspec", chanspec).put("samsung", samsung);
        } catch (JSONException e) { throw new IOException(e); }
        persist();
        monitorInterface = selected;
        if (!wasMonitor) {
            if (!profile.equals("generic")) {
                hostRequire("sh " + WifiCommands.quote(scripts.start.getAbsolutePath()), 35);
            } else {
                if (internal) hostRequire("svc wifi disable", 10);
                runner.require("ip link set " + WifiCommands.quote(selected) + " down && iw dev "
                        + WifiCommands.quote(selected) + " set type monitor", 10);
            }
        }
        runner.require("ip link set " + WifiCommands.quote(selected) + " up", 10);
        WirelessInterface after = find(discover(), selected);
        // Scripts used here change the selected interface in place. Do not borrow an unrelated VIF.
        if (after == null || !after.phy.equals(radio.phy)) throw new IOException("Selected adapter changed during monitor setup");
        boolean monitor = after.type.equals("monitor");
        if (profile.equals("qcacld")) monitor = hostRequire("cat " + WifiCommands.quote(conPath), 5).trim().equals("4");
        if (nexmon) monitor = readNexmonMode(samsung) > 0;
        if (!monitor || !isUp(selected)) throw new IOException("Adapter did not enter monitor mode");
        if (channel > 0) setChannel(channel);
        return selected;
    }

    public void setChannel(int channel) throws IOException {
        WifiCommands.channel(channel);
        String name = WifiCommands.quote(WifiCommands.iface(monitorInterface));
        if (nexmon) {
            hostRequire("nexutil -I " + name + " -k" + channel, 10);
            String description = hostRequire("nexutil -I " + name + " -k", 5);
            Matcher match = Pattern.compile("chanspec:\\s*0x[0-9a-fA-F]+,\\s*(\\d+)").matcher(description);
            if (!match.find() || Integer.parseInt(match.group(1)) != channel) throw new IOException("Adapter did not switch to the requested channel");
        } else {
            runner.require("iw dev " + name + " set channel " + channel, 10);
            WirelessInterface current = find(discover(), monitorInterface);
            if (current == null || current.channel != channel) throw new IOException("Adapter did not switch to the requested channel");
        }
    }

    public String toolPrefix() throws IOException {
        if (!nexmon) return "";
        runner.require("test -r /lib/libnexmonkali.so", 5);
        return "export LD_PRELOAD=/lib/libnexmonkali.so; ";
    }

    private void persist() throws IOException {
        if (!recovery.edit().putString("original", original.toString()).commit()) {
            throw new IOException("Could not save radio recovery state");
        }
    }

    public void restore() throws IOException {
        if (original == null) return;
        String name = WifiCommands.iface(original.optString("name"));
        String quoted = WifiCommands.quote(name);
        WirelessInterface current = find(discover(), name);
        if (current == null) throw new IOException("Reconnect " + name + " and use Restore interrupted session");
        String path = hostRequire("readlink -f /sys/class/net/" + quoted + "/device", 5).trim();
        if (!path.equals(original.optString("devicePath"))) throw new IOException("The adapter at " + name + " changed; saved state was retained");
        String profile = original.optString("profile");
        if (profile.equals("generic") && !original.optBoolean("internal")) {
            String address = WifiCommands.mac(hostRequire("cat /sys/class/net/" + quoted + "/address", 5));
            if (!address.equals(original.optString("address"))) throw new IOException("The adapter address changed; saved state was retained");
        }
        if (!original.optBoolean("wasMonitor")) {
            if (profile.equals("generic")) {
                runner.require("ip link set " + quoted + " down && iw dev " + quoted + " set type managed", 10);
            } else {
                MonitorModeScripts.Scripts scripts = monitorScripts.prepare();
                hostRequire("sh " + WifiCommands.quote(scripts.stop.getAbsolutePath()), 35);
                if (profile.equals("nexmon")) {
                    setNexmonMode(original.optBoolean("samsung"), original.optInt("nexMode"));
                }
            }
        }
        if (profile.equals("nexmon") && (original.optBoolean("wasMonitor") || original.optString("wifi").equals("0"))) {
            String spec = original.optString("chanspec");
            if (!spec.matches("0x[0-9a-fA-F]{4}")) throw new IOException("Invalid saved Nexmon channel");
            hostRequire("nexutil -I " + quoted + " -i -k" + spec, 10);
            if (readNexmonMode(original.optBoolean("samsung")) != original.optInt("nexMode")
                    || !readChanspec().equalsIgnoreCase(spec)) throw new IOException("Nexmon mode/channel restoration could not be verified");
        } else if (original.optBoolean("wasMonitor") && original.optInt("channel") > 0) {
            runner.require("iw dev " + quoted + " set channel " + WifiCommands.channel(original.optInt("channel")), 10);
        }
        if (profile.equals("qcacld")) {
            String mode = hostRequire("cat " + WifiCommands.quote(original.optString("conPath")), 5).trim();
            if (!mode.equals(String.valueOf(original.optInt("conMode")))) throw new IOException("QCACLD mode restoration could not be verified");
        }
        if (original.optBoolean("internal") && !original.optBoolean("wasMonitor")) {
            String wifi = original.optString("wifi");
            hostRequire("svc wifi " + (wifi.equals("0") ? "disable" : "enable"), 10);
            String watch = original.optString("watchWifi");
            hostRequire(watch.equals("null") ? "settings delete system clockwork_wifi_setting"
                    : "settings put system clockwork_wifi_setting " + WifiCommands.quote(watch), 5);
            String expected = wifi;
            hostRequire("for i in 1 2 3 4 5; do [ \"$(settings get global wifi_on)\" = " + expected
                    + " ] && exit 0; sleep 1; done; echo 'Android Wi-Fi restoration could not be verified'; exit 1", 7);
        }
        runner.require("ip link set " + quoted + (original.optBoolean("up") ? " up" : " down"), 10);
        if (profile.equals("nexmon") && readNexmonMode(original.optBoolean("samsung")) != original.optInt("nexMode")) {
            throw new IOException("Nexmon mode restoration could not be verified");
        }
        current = find(discover(), name);
        if (current == null || isUp(name) != original.optBoolean("up")) throw new IOException("Adapter link state was not restored");
        if (profile.equals("generic") && (!current.type.equals(original.optString("type"))
                || (original.optBoolean("wasMonitor") && original.optInt("channel") > 0
                && current.channel != original.optInt("channel")))) {
            throw new IOException("Adapter mode/channel restoration could not be verified");
        }
        if (!recovery.edit().remove("original").commit()) throw new IOException("Could not clear saved radio state");
        original = null;
    }

    private boolean isUp(String name) throws IOException {
        String link = runner.require("ip -o link show dev " + WifiCommands.quote(WifiCommands.iface(name)), 5);
        return Pattern.compile("<[^>]*\\bUP\\b[^>]*>").matcher(link).find();
    }

    private String hostRequire(String command, int seconds) throws IOException {
        ChrootRunner.Result result = runner.host(command, seconds);
        if (result.code != 0) throw new IOException(result.output.isEmpty() ? "Android radio command failed" : result.output);
        return result.output;
    }

    private int readNexmonMode(boolean samsung) throws IOException {
        String output;
        if (samsung) {
            String od = NhPaths.BUSYBOX.isEmpty() ? "od" : WifiCommands.quote(NhPaths.BUSYBOX) + " od";
            output = hostRequire("set -o pipefail; nexutil -g0x613 -l4 -r | " + od + " -An -tu4", 5).trim();
            if (output.matches("[0-2]")) return Integer.parseInt(output);
        } else {
            output = hostRequire("nexutil -m", 5);
            Matcher match = Pattern.compile("(?m)^monitor:\\s*([0-2])\\s*$").matcher(output);
            if (match.find()) return Integer.parseInt(match.group(1));
        }
        throw new IOException("Cannot read the current Nexmon monitor mode");
    }

    private void setNexmonMode(boolean samsung, int mode) throws IOException {
        if (mode < 0 || mode > 2) throw new IOException("Invalid saved Nexmon mode");
        hostRequire(samsung ? "nexutil -s0x613 -i -v" + mode : "nexutil -m" + mode, 5);
    }

    private String readChanspec() throws IOException {
        Matcher match = Pattern.compile("chanspec:\\s*(0x[0-9a-fA-F]{4})").matcher(hostRequire("nexutil -k", 5));
        if (!match.find()) throw new IOException("Cannot read the current Nexmon channel");
        return match.group(1);
    }

    private static WirelessInterface find(List<WirelessInterface> radios, String name) {
        for (WirelessInterface radio : radios) if (radio.name.equals(name)) return radio;
        return null;
    }
}
