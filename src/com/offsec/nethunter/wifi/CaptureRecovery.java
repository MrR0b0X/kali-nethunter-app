package com.offsec.nethunter.wifi;

import android.content.Context;

import com.offsec.nethunter.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Called only after radio recovery has confirmed that the old supervisors have stopped. */
public final class CaptureRecovery {
    private CaptureRecovery() { }

    public static Result recover(Context context, WifiPaths paths, WifiStore store) {
        int saved = 0, retained = 0;
        for (File directory : paths.sessions()) {
            if (!directory.isDirectory() || !directory.getName().matches("[0-9a-fA-F-]{36}")) continue;
            File capture = new File(directory, "capture-01.cap");
            if (!capture.isFile()) continue;
            try {
                if (!paths.ownsSession(directory)
                        || !capture.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) {
                    throw new IOException("Unexpected session path");
                }
                if (capture.length() < 24 || capture.length() > CaptureFiles.MAX_BYTES) {
                    throw new IOException("Capture is truncated or exceeds 100 MB");
                }
                if (store.captureByDigest(CaptureFiles.sha256(capture)) == null) {
                    JSONObject info = new JSONObject();
                    try { info = new JSONObject(WifiPaths.read(new File(directory, "session.json"), 8192)); }
                    catch (IOException | JSONException ignored) { }
                    WifiStore.Capture archived = store.importCapture(new FileInputStream(capture),
                            info.optString("name", "recovered_" + directory.getName().substring(0, 8) + ".cap"));
                    store.validate(archived.id, "UNVERIFIED", info.optString("target", "")
                            + "\n" + context.getString(R.string.wifi_capture_recovered));
                    saved++;
                }
                WifiPaths.removeSession(directory);
            } catch (IOException | RuntimeException e) {
                retained++;
                WifiSessionState.log(context.getString(R.string.wifi_capture_retained, directory.getAbsolutePath())
                        + "\n" + (e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        return new Result(saved, retained);
    }

    public static final class Result {
        public final int saved, retained;
        Result(int saved, int retained) { this.saved = saved; this.retained = retained; }
    }
}
