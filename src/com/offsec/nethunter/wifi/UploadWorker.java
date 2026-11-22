package com.offsec.nethunter.wifi;

import android.content.ContentValues;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class UploadWorker extends Worker {
    private static final String UPLOAD_ID = "upload_id";
    static final int MAX_ATTEMPTS = 4;
    private volatile OnlineHashCrackClient client;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    public static void schedule(Context context, WifiStore.Upload upload) {
        if (!upload.state.equals("QUEUED")) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(new Data.Builder().putString(UPLOAD_ID, upload.id).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInitialDelay(Math.max(0, upload.nextAttempt - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                .addTag("wifi-upload")
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork("wifi-upload-" + upload.id, ExistingWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context, String id) {
        WifiStore.get(context).cancelUpload(id);
        WorkManager.getInstance(context).cancelUniqueWork("wifi-upload-" + id);
    }

    /** Covers a crash between inserting a queue record and scheduling its WorkRequest. */
    public static void reconcile(Context context) {
        for (WifiStore.Upload upload : WifiStore.get(context).uploads()) schedule(context, upload);
    }

    @NonNull @Override public Result doWork() {
        String id = getInputData().getString(UPLOAD_ID);
        if (id == null) return Result.failure();
        WifiStore store = WifiStore.get(getApplicationContext());
        WifiStore.Upload job = store.upload(id);
        if (job == null || !job.pending()) return Result.success();
        // A cancelled WorkManager run can take a moment to release its connection.
        if (!store.acquireUpload(id)) return Result.retry();
        try {
            if (job.state.equals("UPLOADING")) {
                state(store, id, "UNKNOWN", "Upload was interrupted before acknowledgment. Check the dashboard before retrying.");
                return Result.failure();
            }
            if (job.attempts >= MAX_ATTEMPTS) {
                state(store, id, "FAILED", "Upload retry limit reached.");
                return Result.failure();
            }
            if (job.nextAttempt > System.currentTimeMillis()) return Result.retry();
            File file;
            try {
                file = store.file(store.capture(job.captureId));
                if (!file.isFile() || file.length() < 24 || file.length() > CaptureFiles.MAX_BYTES
                        || !CaptureFiles.sha256(file).equals(job.digest)) {
                    throw new IOException("Capture is missing or changed. Import the file again.");
                }
            } catch (IOException e) {
                // A missing or changed local file cannot be repaired by retrying the network.
                state(store, id, "FAILED", e.getMessage());
                return Result.failure();
            }
            if (isStopped()) return Result.retry();
            ContentValues begin = new ContentValues();
            begin.put("state", "UPLOADING");
            begin.put("attempts", job.attempts + 1);
            begin.put("progress", 0);
            begin.put("message", "");
            if (!store.updateUpload(id, begin)) return Result.success();
            client = new OnlineHashCrackClient();
            if (isStopped()) client.cancel();
            OnlineHashCrackClient.Result outcome = client.upload(file, job.email, percent -> {
                ContentValues progress = new ContentValues();
                progress.put("progress", percent);
                store.updateUpload(id, progress);
                if (isStopped()) client.cancel();
            });
            ContentValues result = new ContentValues();
            boolean retry = outcome.retryable && job.attempts + 1 < MAX_ATTEMPTS;
            result.put("state", retry ? "QUEUED" : outcome.state);
            result.put("http", outcome.http);
            result.put("accepted", outcome.accepted);
            result.put("skipped", outcome.skipped);
            result.put("rejected", outcome.rejected);
            result.put("message", outcome.message);
            result.put("next_attempt", System.currentTimeMillis() + outcome.retryAfterSeconds * 1000L);
            store.updateUpload(id, result);
            return retry ? Result.retry() : Result.success();
        } catch (IOException | RuntimeException e) {
            boolean uncertain = client != null && client.mayHaveBeenSubmitted();
            boolean retry = !uncertain && job.attempts + 1 < MAX_ATTEMPTS
                    && getRunAttemptCount() + 1 < MAX_ATTEMPTS;
            state(store, id, uncertain ? "UNKNOWN" : (retry ? "QUEUED" : "FAILED"),
                    uncertain ? "Connection ended before acknowledgment. Check the dashboard before retrying."
                            : (e.getMessage() == null ? "Upload failed" : e.getMessage()));
            return retry ? Result.retry() : Result.failure();
        } finally {
            if (client != null) client.cancel();
            store.releaseUpload(id);
        }
    }

    private static void state(WifiStore store, String id, String state, String message) {
        ContentValues values = new ContentValues();
        values.put("state", state);
        values.put("message", message == null ? "Upload failed" : message);
        store.updateUpload(id, values);
    }

    @Override public void onStopped() {
        OnlineHashCrackClient active = client;
        if (active != null) active.cancel();
    }
}
