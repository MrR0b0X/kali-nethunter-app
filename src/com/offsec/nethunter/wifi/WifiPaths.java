package com.offsec.nethunter.wifi;

import android.content.Context;

import com.offsec.nethunter.utils.NhPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

public final class WifiPaths {
    private final File root;

    public WifiPaths(Context context) {
        NhPaths.getInstance(context);
        root = new File(NhPaths.APP_SD_FILES_PATH, "wifi");
    }

    WifiPaths(File root) { this.root = root; }

    File[] sessions() {
        File[] files = new File(root, "sessions").listFiles();
        return files == null ? new File[0] : files;
    }

    boolean ownsSession(File directory) throws IOException {
        File canonical = directory.getCanonicalFile();
        return canonical.getParentFile().equals(new File(root, "sessions").getCanonicalFile())
                && canonical.getName().equals(directory.getName());
    }

    public void describeSession(File directory, String name, String target) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(directory, "session.json"))) {
            out.write(new JSONObject().put("name", name).put("target", target).toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (JSONException e) { throw new IOException(e); }
    }

    public File newSession() throws IOException {
        File directory = new File(root, "sessions/" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("Cannot create Wi-Fi session folder. Check storage access.");
        return directory;
    }

    public String guest(File file) throws IOException {
        String parent = root.getCanonicalPath() + File.separator;
        String path = file.getCanonicalPath();
        if (!path.startsWith(parent)) throw new IOException("File is outside the Wi-Fi session folder");
        // This is the Kali bind mount; the Android path above can be /storage/emulated/0.
        return NhPaths.CHROOT_SD_PATH + "/nh_files/wifi/" + path.substring(parent.length());
    }

    public void checkSharedFolder(File directory, ChrootRunner runner) throws IOException {
        File marker = new File(directory, "visibility-check");
        File reply = new File(directory, "visibility-reply");
        String expected = UUID.randomUUID().toString();
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(expected.getBytes(StandardCharsets.US_ASCII));
        }
        try {
            if (!runner.require("cat " + WifiCommands.quote(guest(marker)), 5).equals(expected)) {
                throw new IOException("Android and Kali do not share the capture folder. Remount the chroot.");
            }
            runner.require("printf '%s' " + WifiCommands.quote(expected) + " > " + WifiCommands.quote(guest(reply)), 5);
            if (!read(reply, 128).equals(expected)) throw new IOException("Android cannot read Kali capture output. Remount the chroot.");
        } finally { marker.delete(); reply.delete(); }
    }

    public static void copy(File source, File destination) throws IOException {
        long remaining = source.length();
        if (remaining > CaptureFiles.MAX_BYTES) throw new IOException("Capture exceeds 100 MB");
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int count;
            // Freeze the snapshot length even while airodump is appending packets.
            while (remaining > 0 && (count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, count);
                remaining -= count;
            }
            if (remaining != 0) throw new IOException("Capture changed while it was being read");
        }
    }

    public static String read(File file, int maxBytes) throws IOException {
        if (file.length() > maxBytes) throw new IOException("Tool output exceeds the supported size");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), maxBytes)];
            int offset = 0, count;
            while (offset < bytes.length && (count = in.read(bytes, offset, bytes.length - offset)) != -1) offset += count;
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        }
    }

    public static void removeSession(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File file : children) if (file.isFile()) file.delete();
        directory.delete();
    }
}
