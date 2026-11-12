package com.offsec.nethunter.wifi;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Prepare the APK's radio helpers independently of legacy, first-install asset extraction. */
final class MonitorModeScripts {
    private final AssetManager assets;
    private final File directory;

    MonitorModeScripts(Context context) {
        assets = context.getApplicationContext().getAssets();
        directory = new File(context.getFilesDir(), "wifi/scripts");
    }

    Scripts prepare() throws IOException {
        synchronized (MonitorModeScripts.class) {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot prepare bundled Wi-Fi monitor scripts. Check app storage.");
            }
            // Both directions must be available before changing the adapter.
            File start = prepare("start-monitormode.sh");
            File stop = prepare("stop-monitormode.sh");
            return new Scripts(start, stop);
        }
    }

    private File prepare(String name) throws IOException {
        try {
            byte[] bundled;
            try (InputStream input = assets.open("nh_files/modules/" + name)) { bundled = read(input); }
            File file = new File(directory, name);
            AtomicFile atomic = new AtomicFile(file);
            try (InputStream input = atomic.openRead()) {
                if (Arrays.equals(bundled, read(input))) return file;
            } catch (IOException ignored) {
                // Missing, interrupted, or damaged copies are replaced from this APK.
            }
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(bundled);
                atomic.finishWrite(output);
            } catch (IOException e) {
                atomic.failWrite(output);
                throw e;
            }
            try (InputStream input = atomic.openRead()) {
                if (!Arrays.equals(bundled, read(input))) throw new IOException("Script copy could not be verified");
            }
            return file;
        } catch (IOException e) {
            throw new IOException("Cannot prepare bundled Wi-Fi script " + name + ": " + e.getMessage(), e);
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 65536) throw new IOException("Monitor script exceeds the supported size");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static final class Scripts {
        final File start, stop;
        Scripts(File start, File stop) { this.start = start; this.stop = stop; }
    }
}
