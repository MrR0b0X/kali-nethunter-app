package com.offsec.nethunter.wifi;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public WPA API: normal Android TLS verification, one capture, no shell or API key. */
public final class OnlineHashCrackClient {
    public static final String ENDPOINT = "https://api.onlinehashcrack.com";
    public static final String DASHBOARD = "https://onlinehashcrack.com/tasks";
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "wifi-upload-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final URL endpoint;
    private volatile HttpURLConnection connection;
    private volatile boolean bodyStarted;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public interface Progress { void update(int percent); }

    public OnlineHashCrackClient() throws IOException { this(new URL(ENDPOINT)); }
    // Package visibility allows local HTTP fixtures without a configurable production endpoint.
    OnlineHashCrackClient(URL endpoint) { this.endpoint = endpoint; }

    public boolean mayHaveBeenSubmitted() { return bodyStarted; }

    public void cancel() {
        cancelled.set(true);
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
    }

    public Result upload(File file, String email, Progress progress) throws IOException {
        if (email == null || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IOException("Enter your OnlineHashCrack account email");
        }
        if (!file.isFile() || file.length() < 24 || file.length() > CaptureFiles.MAX_BYTES) {
            throw new IOException("Missing, truncated, or oversized capture");
        }
        byte[] magic = new byte[4];
        try (InputStream input = new FileInputStream(file)) {
            if (input.read(magic) != 4) throw new IOException("Capture is truncated");
        }
        String extension = CaptureFiles.extension(magic);
        String boundary = "NetHunter" + UUID.randomUUID().toString().replace("-", "");
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"email\"\r\n\r\n"
                + email + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"capture" + extension
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpURLConnection active = (HttpURLConnection) endpoint.openConnection();
        connection = active;
        active.setRequestMethod("POST");
        active.setInstanceFollowRedirects(false);
        active.setConnectTimeout(20_000);
        active.setReadTimeout(120_000);
        active.setDoOutput(true);
        active.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        active.setRequestProperty("Accept", "application/json");
        active.setRequestProperty("User-Agent", "NetHunter-WiFi/1");
        active.setFixedLengthStreamingMode(prefix.length + file.length() + suffix.length);
        ScheduledFuture<?> deadline = DEADLINES.schedule(active::disconnect, 4, TimeUnit.MINUTES);
        try {
            checkCancelled();
            try (OutputStream out = active.getOutputStream(); InputStream in = new FileInputStream(file)) {
                checkCancelled();
                bodyStarted = true;
                out.write(prefix);
                byte[] buffer = new byte[65536];
                long sent = 0;
                int count;
                int lastProgress = -1;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled();
                    out.write(buffer, 0, count);
                    sent += count;
                    int percent = (int) Math.min(99, sent * 100 / file.length());
                    if (percent != lastProgress) {
                        progress.update(percent);
                        lastProgress = percent;
                    }
                }
                out.write(suffix);
            }
            checkCancelled();
            int http = active.getResponseCode();
            InputStream stream = http >= 400 ? active.getErrorStream() : active.getInputStream();
            String body = readResponse(stream);
            Result result = parse(http, body);
            String retry = active.getHeaderField("Retry-After");
            if (retry != null && retry.matches("\\d{1,6}")) {
                result.retryAfterSeconds = Math.min(86400, Integer.parseInt(retry));
            }
            progress.update(100);
            return result;
        } finally {
            deadline.cancel(false);
            active.disconnect();
            connection = null;
        }
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("Upload interrupted");
    }

    private static String readResponse(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 2 * 1024 * 1024) throw new IOException("API response is too large");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static Result parse(int http, String body) {
        boolean transientFailure = http == 408 || http == 429 || http >= 500;
        try {
            JSONObject json = new JSONObject(body);
            if (http < 200 || http >= 300) {
                return new Result("FAILED", http, 0, 0, 0,
                        json.optString("message", "HTTP " + http), transientFailure);
            }
            JSONObject accepted = json.getJSONObject("accepted");
            JSONObject skipped = json.getJSONObject("skipped");
            JSONObject rejected = json.getJSONObject("rejected");
            int a = count(accepted), s = count(skipped), r = count(rejected);
            if (a + s + r == 0) return new Result("REJECTED", http, 0, 0, 0, "no_hash_found", false);
            boolean unknownSkip = s > 0 && !skipped.optString("reason", "").equals("already_sent");
            String state = unknownSkip ? (a > 0 ? "PARTIAL" : "UNKNOWN")
                    : r > 0 ? (a + s > 0 ? "PARTIAL" : "REJECTED")
                    : (a > 0 ? "ACCEPTED" : "DUPLICATE");
            String message = rejected.optString("message", rejected.optString("reason", ""));
            if (s > 0) message += (message.isEmpty() ? "" : "\n") + skipped.optString("reason", "Unrecognized skipped items");
            return new Result(state, http, a, s, r, message, false);
        } catch (JSONException | IllegalArgumentException e) {
            // An unparseable 2xx can follow a successful upload; never report false success.
            return new Result(http >= 200 && http < 300 ? "UNKNOWN" : "FAILED", http,
                    0, 0, 0, "Unexpected API response (HTTP " + http + ")", transientFailure);
        }
    }

    private static int count(JSONObject bucket) throws JSONException {
        Object raw = bucket.get("count");
        if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new IllegalArgumentException("Invalid count");
        long value = ((Number) raw).longValue();
        if (value < 0 || value > 1_000_000) throw new IllegalArgumentException("Invalid count");
        return (int) value;
    }

    public static final class Result {
        public final String state, message;
        public final int http, accepted, skipped, rejected;
        public final boolean retryable;
        public int retryAfterSeconds;

        Result(String state, int http, int accepted, int skipped, int rejected, String message, boolean retryable) {
            this.state = state; this.http = http; this.accepted = accepted;
            this.skipped = skipped; this.rejected = rejected; this.retryable = retryable;
            this.message = message == null ? "" : message.length() > 2048 ? message.substring(0, 2048) : message;
        }
    }
}
