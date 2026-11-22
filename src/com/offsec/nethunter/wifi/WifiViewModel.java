package com.offsec.nethunter.wifi;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.offsec.nethunter.R;
import com.offsec.nethunter.service.WifiSessionService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class WifiViewModel extends AndroidViewModel {
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    private final WifiStore store;
    // No value until the first read completes: an empty library is different from a loading library.
    public final MutableLiveData<List<WifiStore.Capture>> captures = new MutableLiveData<>();
    public final MutableLiveData<List<WifiStore.Upload>> uploads = new MutableLiveData<>(Collections.emptyList());
    public final MutableLiveData<List<WirelessInterface>> interfaces = new MutableLiveData<>(Collections.emptyList());
    public final MutableLiveData<Boolean> working = new MutableLiveData<>(false);
    public final MutableLiveData<Event> messages = new MutableLiveData<>();
    private volatile boolean discovering, cleared;
    private final AtomicInteger pending = new AtomicInteger();
    private static final Map<Uri, Integer> IMPORT_PERMISSIONS = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    public WifiViewModel(@NonNull Application application) {
        super(application);
        store = WifiStore.get(application);
        refresh();
        files.execute(() -> UploadWorker.reconcile(application));
    }

    public LiveData<Long> changes() { return store.changes(); }

    public synchronized void refresh() {
        if (cleared) return;
        reads.execute(() -> {
            try {
                captures.postValue(store.captures());
                uploads.postValue(store.uploads());
            } catch (RuntimeException e) { message(e.getMessage()); }
        });
    }

    public void discover() {
        if (discovering || WifiSessionState.active()) return;
        discovering = true;
        execute(() -> {
            try { interfaces.postValue(new InterfaceController(getApplication(), new ChrootRunner(getApplication())).discover()); }
            finally { discovering = false; }
        });
    }

    public void restoreRadio() {
        if (!WifiSessionState.begin(selectedInterface(), false)) { message(getApplication().getString(R.string.wifi_busy)); return; }
        execute(() -> {
            String status = getApplication().getString(R.string.wifi_radio_restored);
            boolean error = false;
            try {
                WifiSessionState.stage(getApplication().getString(R.string.wifi_stage_restoring), 0);
                new InterfaceController(getApplication(), new ChrootRunner(getApplication())).recover();
                CaptureRecovery.Result recovered = CaptureRecovery.recover(getApplication(), new WifiPaths(getApplication()), store);
                status += "\n" + getApplication().getString(R.string.wifi_recovered_files, recovered.saved, recovered.retained);
            } catch (IOException | RuntimeException e) {
                error = true;
                status = e.getMessage();
                throw e;
            } finally { WifiSessionState.finish(status, error); }
        });
    }

    public String selectedInterface() { return preferences().getString("interface", ""); }
    public void selectedInterface(String iface) { preferences().edit().putString("interface", iface).apply(); }
    public String email() { return preferences().getString("email", ""); }
    public void email(String email) { preferences().edit().putString("email", email.trim()).apply(); }

    private android.content.SharedPreferences preferences() {
        return getApplication().getSharedPreferences("wifi_settings", Context.MODE_PRIVATE);
    }

    public boolean start(WifiSessionRequest request) {
        if (pending.get() > 0) { message(getApplication().getString(R.string.wifi_wait_for_operation)); return false; }
        try { WifiSessionService.start(getApplication(), request); return true; }
        catch (RuntimeException e) { message(e.getMessage()); return false; }
    }

    public void importCaptures(List<Uri> uris) {
        if (cleared || uris == null || uris.isEmpty()) return;
        List<Uri> documents = new ArrayList<>(new LinkedHashSet<>(uris));
        Set<Uri> persistent = new HashSet<>();
        for (Uri uri : documents) if (acquireImportPermission(uri)) persistent.add(uri);
        execute(() -> {
            int imported = 0;
            List<String> errors = new ArrayList<>();
            for (Uri uri : documents) {
                String name = "capture.cap";
                try {
                    try (Cursor c = getApplication().getContentResolver().query(uri,
                            new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst()) name = c.getString(0);
                    }
                    InputStream input = getApplication().getContentResolver().openInputStream(uri);
                    if (input == null) throw new IOException("Document could not be opened");
                    store.importCapture(input, name);
                    imported++;
                } catch (IOException | RuntimeException e) {
                    errors.add(CaptureFiles.displayName(name) + ": " + e.getMessage());
                } finally {
                    if (persistent.remove(uri)) releaseImportPermission(uri);
                }
            }
            message(getApplication().getString(R.string.wifi_import_result, imported, errors.size())
                    + (errors.isEmpty() ? "" : "\n" + String.join("\n", errors)));
        });
    }

    private boolean acquireImportPermission(Uri uri) {
        synchronized (IMPORT_PERMISSIONS) {
            Integer references = IMPORT_PERMISSIONS.get(uri);
            if (references != null) {
                IMPORT_PERMISSIONS.put(uri, references + 1);
                return true;
            }
            try {
                for (UriPermission permission : getApplication().getContentResolver().getPersistedUriPermissions()) {
                    if (permission.isReadPermission() && permission.getUri().equals(uri)) return false;
                }
                getApplication().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                IMPORT_PERMISSIONS.put(uri, 1);
                return true;
            } catch (SecurityException ignored) {
                // Some providers offer only a temporary grant; use it while available.
                return false;
            }
        }
    }

    private void releaseImportPermission(Uri uri) {
        synchronized (IMPORT_PERMISSIONS) {
            Integer references = IMPORT_PERMISSIONS.get(uri);
            if (references == null) return;
            if (references > 1) {
                IMPORT_PERMISSIONS.put(uri, references - 1);
                return;
            }
            IMPORT_PERMISSIONS.remove(uri);
            try { getApplication().getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
        }
    }

    public void validate(WifiStore.Capture capture) {
        execute(() -> {
            WifiPaths paths = new WifiPaths(getApplication());
            ChrootRunner runner = new ChrootRunner(getApplication());
            File work = paths.newSession();
            try {
                paths.checkSharedFolder(work, runner);
                File staged = new File(work, "capture.cap");
                WifiPaths.copy(store.file(capture), staged);
                CaptureValidator.Verification result = new CaptureValidator(runner, paths).verify(staged, "", "");
                store.validate(capture.id, result.state, result.details);
            } finally { WifiPaths.removeSession(work); }
        });
    }

    public void rename(WifiStore.Capture capture, String name) { execute(() -> store.rename(capture.id, name)); }
    public void delete(WifiStore.Capture capture) { execute(() -> store.delete(capture.id)); }
    public void cancel(WifiStore.Upload upload) { execute(() -> UploadWorker.cancel(getApplication(), upload.id)); }

    public void submit(String captureId, String email) {
        execute(() -> {
            WifiStore.Upload upload = store.enqueue(captureId, email);
            UploadWorker.schedule(getApplication(), upload);
            message(getApplication().getString(upload.state.equals("QUEUED")
                    ? R.string.wifi_upload_queued : R.string.wifi_upload_already_recorded));
        });
    }

    private interface Operation { void run() throws IOException; }

    private void execute(Operation operation) {
        if (cleared) return;
        pending.incrementAndGet();
        working.setValue(true);
        files.execute(() -> {
            try { operation.run(); }
            catch (IOException | RuntimeException e) {
                Log.e("WifiViewModel", "Wi-Fi operation failed", e);
                message(e.getMessage());
            }
            finally {
                pending.decrementAndGet();
                main.post(() -> working.setValue(pending.get() > 0));
                refresh();
            }
        });
    }

    private void message(String text) {
        messages.postValue(new Event(text == null ? getApplication().getString(R.string.wifi_operation_failed) : text));
    }

    @Override protected synchronized void onCleared() {
        // Finish an import/archive already in progress; it must not be tied to a View lifetime.
        cleared = true;
        files.shutdown();
        reads.shutdown();
    }

    public static final class Event {
        private final String text;
        private boolean consumed;
        Event(String text) { this.text = text; }
        public synchronized String consume() {
            if (consumed) return null;
            consumed = true;
            return text;
        }
    }
}
