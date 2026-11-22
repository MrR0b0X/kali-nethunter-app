package com.offsec.nethunter.wifi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class CaptureFiles {
    public static final long MAX_BYTES = 100_000_000L;

    private CaptureFiles() { }

    public static String extension(byte[] header) throws IOException {
        if (header.length < 4) throw new IOException("Capture is truncated");
        int magic = ((header[0] & 255) << 24) | ((header[1] & 255) << 16)
                | ((header[2] & 255) << 8) | (header[3] & 255);
        if (magic == 0x0a0d0d0a) return ".pcapng";
        if (magic == 0xa1b2c3d4 || magic == 0xd4c3b2a1 || magic == 0xa1b23c4d || magic == 0x4d3cb2a1) {
            return ".cap";
        }
        throw new IOException("Select a CAP, PCAP, or PCAPNG capture file");
    }

    public static String displayName(String name) {
        String clean = name == null ? "capture.cap" : name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        if (clean.isEmpty()) clean = "capture.cap";
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    public static String sha256(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
}
