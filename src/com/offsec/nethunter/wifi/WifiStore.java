package com.offsec.nethunter.wifi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Private immutable capture files and durable, account-specific upload records. */
public final class WifiStore extends SQLiteOpenHelper {
    private static WifiStore instance;
    private final File captures;
    private final MutableLiveData<Long> changes = new MutableLiveData<>(0L);
    private final Set<String> activeUploads = new HashSet<>();

    public static synchronized WifiStore get(Context context) {
        if (instance == null) instance = new WifiStore(context.getApplicationContext());
        return instance;
    }

    public WifiStore(Context context) {
        super(context, "wifi_captures.db", null, 1);
        captures = new File(context.getFilesDir(), "wifi/captures");
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE captures (id TEXT PRIMARY KEY, name TEXT NOT NULL, file TEXT NOT NULL UNIQUE, "
                + "created INTEGER NOT NULL, size INTEGER NOT NULL, digest TEXT NOT NULL, "
                + "validation TEXT NOT NULL DEFAULT 'UNVERIFIED', details TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE uploads (id TEXT PRIMARY KEY, capture_id TEXT NOT NULL REFERENCES captures(id) "
                + "ON DELETE RESTRICT, email TEXT NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL, "
                + "created INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, progress INTEGER NOT NULL DEFAULT 0, "
                + "accepted INTEGER NOT NULL DEFAULT 0, skipped INTEGER NOT NULL DEFAULT 0, "
                + "rejected INTEGER NOT NULL DEFAULT 0, http INTEGER NOT NULL DEFAULT 0, "
                + "next_attempt INTEGER NOT NULL DEFAULT 0, message TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX upload_capture ON uploads(capture_id)");
        db.execSQL("CREATE INDEX upload_identity ON uploads(email,digest)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unknown Wi-Fi database version");
    }

    public LiveData<Long> changes() { return changes; }
    private void changed() { changes.postValue(System.nanoTime()); }

    public synchronized Capture importCapture(InputStream input, String name) throws IOException {
        if (!captures.isDirectory() && !captures.mkdirs()) {
            input.close();
            throw new IOException("Cannot create capture library");
        }
        String id = UUID.randomUUID().toString();
        File partial = new File(captures, id + ".part");
        File complete = null;
        long size = 0;
        MessageDigest digest = CaptureFiles.digest();
        byte[] header = new byte[24];
        int headerSize = 0;
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                size += count;
                if (size > CaptureFiles.MAX_BYTES) throw new IOException("Capture exceeds the 100 MB upload limit");
                int keep = Math.min(count, header.length - headerSize);
                System.arraycopy(buffer, 0, header, headerSize, keep);
                headerSize += keep;
                digest.update(buffer, 0, count);
                out.write(buffer, 0, count);
            }
            if (headerSize < 24) throw new IOException("Capture is truncated");
            complete = new File(captures, id + CaptureFiles.extension(header));
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            partial.delete();
            throw e;
        }
        if (!partial.renameTo(complete)) {
            partial.delete();
            throw new IOException("Could not save capture");
        }
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("name", CaptureFiles.displayName(name));
        values.put("file", complete.getName());
        values.put("created", System.currentTimeMillis());
        values.put("size", size);
        values.put("digest", CaptureFiles.hex(digest.digest()));
        try {
            getWritableDatabase().insertOrThrow("captures", null, values);
        } catch (RuntimeException e) {
            complete.delete();
            throw e;
        }
        changed();
        return capture(id);
    }

    public List<Capture> captures() {
        List<Capture> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("captures", null, null, null, null, null, "created DESC")) {
            while (c.moveToNext()) list.add(new Capture(c));
        }
        return list;
    }

    public Capture capture(String id) {
        try (Cursor c = getReadableDatabase().query("captures", null, "id=?", new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? new Capture(c) : null;
        }
    }

    Capture captureByDigest(String digest) {
        try (Cursor c = getReadableDatabase().query("captures", null, "digest=?", new String[]{digest}, null, null, "created DESC")) {
            while (c.moveToNext()) {
                Capture capture = new Capture(c);
                try { if (file(capture).isFile()) return capture; } catch (IOException ignored) { }
            }
        }
        return null;
    }

    public File file(Capture capture) throws IOException {
        if (capture == null) throw new IOException("Capture no longer exists");
        File file = new File(captures, capture.file);
        if (!file.getCanonicalFile().getParentFile().equals(captures.getCanonicalFile())) {
            throw new IOException("Invalid capture path");
        }
        return file;
    }

    public synchronized void rename(String id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", CaptureFiles.displayName(name));
        getWritableDatabase().update("captures", v, "id=?", new String[]{id});
        changed();
    }

    public synchronized void validate(String id, String state, String details) {
        ContentValues v = new ContentValues();
        v.put("validation", state);
        v.put("details", details == null ? "" : details);
        getWritableDatabase().update("captures", v, "id=?", new String[]{id});
        changed();
    }

    public synchronized void delete(String id) throws IOException {
        for (String active : activeUploads) {
            Upload job = upload(active);
            if (job != null && job.captureId.equals(id)) {
                throw new IOException("Wait for the upload to stop before deleting this capture");
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM uploads WHERE capture_id=? "
                + "AND state IN ('QUEUED','UPLOADING')", new String[]{id})) {
            if (c.moveToFirst()) throw new IOException("Cancel this capture's pending uploads before deleting it");
        }
        Capture capture = capture(id);
        if (capture == null) return;
        File file = file(capture);
        if (file.exists() && !file.delete()) throw new IOException("Could not delete capture");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("uploads", "capture_id=?", new String[]{id});
            db.delete("captures", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        changed();
    }

    public synchronized Upload enqueue(String captureId, String email) throws IOException {
        Capture capture = capture(captureId);
        if (!file(capture).isFile()) throw new IOException("Capture file is missing");
        String account = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (account.length() > 254 || !account.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IOException("Enter your OnlineHashCrack account email");
        }
        for (String active : activeUploads) {
            Upload job = upload(active);
            if (job != null && job.email.equals(account) && job.digest.equals(capture.digest)
                    && !job.pending()) throw new IOException("The previous upload is still stopping. Try again shortly.");
        }
        try (Cursor c = getReadableDatabase().query("uploads", null,
                "email=? AND digest=? AND state IN ('QUEUED','UPLOADING','ACCEPTED','DUPLICATE','PARTIAL')",
                new String[]{account, capture.digest}, null, null, "created DESC", "1")) {
            if (c.moveToFirst()) return new Upload(c);
        }
        ContentValues v = new ContentValues();
        String id = UUID.randomUUID().toString();
        v.put("id", id);
        v.put("capture_id", captureId);
        v.put("email", account);
        v.put("digest", capture.digest);
        v.put("state", "QUEUED");
        v.put("created", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("uploads", null, v);
        changed();
        return upload(id);
    }

    public List<Upload> uploads() {
        List<Upload> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("uploads", null, null, null, null, null, "created DESC")) {
            while (c.moveToNext()) list.add(new Upload(c));
        }
        return list;
    }

    public Upload upload(String id) {
        try (Cursor c = getReadableDatabase().query("uploads", null, "id=?", new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? new Upload(c) : null;
        }
    }

    public synchronized boolean updateUpload(String id, ContentValues values) {
        int count = getWritableDatabase().update("uploads", values,
                "id=? AND state IN ('QUEUED','UPLOADING')", new String[]{id});
        changed();
        return count != 0;
    }

    synchronized boolean acquireUpload(String id) { return activeUploads.add(id); }
    synchronized void releaseUpload(String id) { activeUploads.remove(id); }

    public synchronized void cancelUpload(String id) {
        ContentValues v = new ContentValues();
        v.put("state", "CANCELLED");
        // An acknowledged upload is already delivered and cannot be recalled here.
        getWritableDatabase().update("uploads", v,
                "id=? AND state IN ('QUEUED','UPLOADING','UNKNOWN','FAILED')", new String[]{id});
        changed();
    }

    private static String text(Cursor c, String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
    private static long number(Cursor c, String name) { return c.getLong(c.getColumnIndexOrThrow(name)); }

    public static final class Capture {
        public final String id, name, file, digest, validation, details;
        public final long created, size;
        Capture(Cursor c) {
            id = text(c, "id"); name = text(c, "name"); file = text(c, "file");
            digest = text(c, "digest"); validation = text(c, "validation"); details = text(c, "details");
            created = number(c, "created"); size = number(c, "size");
        }
    }

    public static final class Upload {
        public final String id, captureId, email, digest, state, message;
        public final long created, nextAttempt;
        public final int attempts, progress, accepted, skipped, rejected, http;
        Upload(Cursor c) {
            id = text(c, "id"); captureId = text(c, "capture_id"); email = text(c, "email");
            digest = text(c, "digest"); state = text(c, "state"); message = text(c, "message");
            created = number(c, "created"); attempts = (int) number(c, "attempts");
            progress = (int) number(c, "progress"); accepted = (int) number(c, "accepted");
            skipped = (int) number(c, "skipped"); rejected = (int) number(c, "rejected");
            http = (int) number(c, "http");
            nextAttempt = number(c, "next_attempt");
        }
        public boolean pending() { return state.equals("QUEUED") || state.equals("UPLOADING"); }
    }
}
