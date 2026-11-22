package com.offsec.nethunter.wifi;

import android.content.Context;
import android.os.SystemClock;

import com.offsec.nethunter.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serial orchestration. Each radio tool also has an independent in-chroot deadline. */
public final class WifiSessionEngine {
    private final Context context;
    private final ChrootRunner runner;
    private final InterfaceController interfaces;
    private final WifiPaths paths;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile ChrootRunner.OwnedProcess captureProcess, deauthProcess;
    private long started;
    private final Progress progress;

    public interface Progress { void update(String message, int seconds); }

    public WifiSessionEngine(Context context, Progress progress) {
        this.context = context.getApplicationContext();
        runner = new ChrootRunner(context);
        interfaces = new InterfaceController(context, runner);
        paths = new WifiPaths(context);
        this.progress = progress;
    }

    public void cancel() {
        cancelled.set(true);
        try { stopProcesses(); }
        catch (IOException e) { WifiSessionState.log(e.getMessage()); }
    }

    private void stage(int text) {
        progress.update(context.getString(text), (int) ((SystemClock.elapsedRealtime() - started) / 1000));
    }

    public String run(WifiSessionRequest request) throws IOException {
        started = SystemClock.elapsedRealtime();
        IOException failure = null;
        String result = context.getString(R.string.wifi_session_stopped);
        File workspace = null, capture = null;
        String captureName = "";
        String verificationClient = request.client;
        boolean archived = false, stopped = true;
        CaptureValidator.Verification verified = null;
        try {
            stage(R.string.wifi_stage_preparing);
            if (request.mode == WifiSessionRequest.Mode.SCAN) WifiSessionState.networks(Collections.emptyList());
            else if (request.mode == WifiSessionRequest.Mode.CLIENT_SCAN) {
                WifiSessionState.networks(resetClientObservations(WifiSessionState.current().networks, request.bssid));
            }
            interfaces.recover();
            CaptureRecovery.Result recovered = CaptureRecovery.recover(context, paths, WifiStore.get(context));
            if (recovered.saved > 0 || recovered.retained > 0) {
                WifiSessionState.log(context.getString(R.string.wifi_recovered_files, recovered.saved, recovered.retained));
            }
            checkCancelled();
            interfaces.checkTools(request.mode == WifiSessionRequest.Mode.DEAUTH || request.assist);
            if (request.mode == WifiSessionRequest.Mode.PMKID_CAPTURE) PmkidCapturePlan.checkTools(runner);
            checkCancelled();
            if (request.mode != WifiSessionRequest.Mode.DEAUTH) {
                workspace = paths.newSession();
                paths.checkSharedFolder(workspace, runner);
                if (request.mode == WifiSessionRequest.Mode.CAPTURE || request.mode == WifiSessionRequest.Mode.PMKID_CAPTURE) {
                    capture = new File(workspace, "capture-01.cap");
                    String date = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                    captureName = (request.ssid.isEmpty() ? request.bssid : request.ssid) + "_" + date
                            + (request.mode == WifiSessionRequest.Mode.PMKID_CAPTURE ? "_PMKID.pcapng" : ".cap");
                    paths.describeSession(workspace, captureName, request.bssid + " · " + request.ssid
                            + (request.client.isEmpty() ? "" : "\n" + context.getString(R.string.wifi_client) + ": " + request.client));
                }
            }
            String iface = interfaces.prepare(request.iface, request.channel);
            checkCancelled();
            String prefix = interfaces.toolPrefix();
            if (request.mode == WifiSessionRequest.Mode.DEAUTH) {
                deauth(request, iface, prefix);
                result = context.getString(cancelled.get() ? R.string.wifi_session_stopped : R.string.wifi_deauth_finished);
            } else if (request.mode == WifiSessionRequest.Mode.PMKID_CAPTURE) {
                int frequency = PmkidCapturePlan.frequencyFromInfo(runner.require("iw dev "
                        + WifiCommands.quote(iface) + " info", 5), request.channel);
                PmkidCapturePlan plan = new PmkidCapturePlan(request, iface, paths.guest(capture),
                        paths.guest(new File(workspace, "pmkid.bpf")), frequency);
                verificationClient = plan.client;
                runner.require(plan.compileFilterCommand(), 5);
                checkCancelled();
                verified = collectPmkid(plan, prefix, capture);
            } else {
                verified = collect(request, iface, prefix, workspace, capture);
                if ((request.mode == WifiSessionRequest.Mode.SCAN
                        || request.mode == WifiSessionRequest.Mode.CLIENT_SCAN) && !cancelled.get()) {
                    result = scanResult(context, request, WifiSessionState.current().networks);
                }
            }
        } catch (Cancelled ignored) {
            // Cleanup and archival below also run when cancellation happens during startup.
        } catch (IOException | RuntimeException e) {
            failure = asIOException(e);
        } finally {
            try { stopProcesses(); }
            catch (IOException e) { failure = combine(failure, e); stopped = false; }
            if (stopped) {
                stage(R.string.wifi_stage_restoring);
                try { interfaces.restore(); }
                catch (IOException | RuntimeException e) { failure = combine(failure, asIOException(e)); }
            }

            // Even a failed deauth burst or an interrupted capture may have useful packets.
            // Never delete staging until the private library has accepted the capture.
            if (stopped && capture != null && capture.isFile() && capture.length() >= 24) {
                try {
                    stage(R.string.wifi_stage_saving);
                    WifiStore store = WifiStore.get(context);
                    WifiStore.Capture saved = store.importCapture(new FileInputStream(capture), captureName);
                    archived = true;
                    WifiSessionState.captureSaved(saved.id, "UNVERIFIED");
                    if (needsFinalVerification(request.mode, verified)) {
                        if (cancelled.get()) {
                            verified = new CaptureValidator.Verification("UNVERIFIED",
                                    context.getString(R.string.wifi_capture_interrupted), 0);
                        } else {
                            stage(R.string.wifi_stage_verifying);
                            try {
                                CaptureValidator validator = new CaptureValidator(runner, paths);
                                verified = validator.verify(capture, request.bssid, verificationClient);
                            }
                            catch (IOException e) { verified = new CaptureValidator.Verification("UNVERIFIED", e.getMessage(), 0); }
                        }
                    }
                    String target = request.bssid + (request.ssid.isEmpty() ? "" : " · " + request.ssid);
                    if (!request.client.isEmpty()) target += "\n" + context.getString(R.string.wifi_client) + ": " + request.client;
                    store.validate(saved.id, verified.state, verified.usable() ? verified.details
                            : target + (verified.details.isEmpty() ? "" : "\n" + verified.details));
                    WifiSessionState.captureSaved(saved.id, verified.state);
                    if (request.mode == WifiSessionRequest.Mode.PMKID_CAPTURE && !cancelled.get()) {
                        result = context.getString(verified.hasPmkid() ? R.string.wifi_pmkid_saved
                                : R.string.wifi_pmkid_not_found, saved.name);
                    } else {
                        result = context.getString(verified.usable() ? R.string.wifi_capture_saved_verified
                                : R.string.wifi_capture_saved_unverified, saved.name);
                    }
                    WifiSessionState.log(result);
                } catch (IOException | RuntimeException e) { failure = combine(failure, asIOException(e)); }
            } else if (stopped && capture != null && !cancelled.get() && failure == null) {
                failure = new IOException(context.getString(R.string.wifi_no_capture));
            }
            boolean retain = workspace != null && (!stopped || (capture != null && capture.length() > 0 && !archived));
            if (retain) {
                String kept = context.getString(R.string.wifi_capture_retained, workspace.getAbsolutePath());
                WifiSessionState.log(kept);
                if (failure != null) failure = combine(failure, new IOException(kept));
            } else if (workspace != null) WifiPaths.removeSession(workspace);
        }
        if (failure != null) throw failure;
        return result;
    }

    static boolean needsFinalVerification(WifiSessionRequest.Mode mode, CaptureValidator.Verification verified) {
        return verified == null || !verified.usable()
                || (mode == WifiSessionRequest.Mode.PMKID_CAPTURE && !verified.hasPmkid());
    }

    private CaptureValidator.Verification collectPmkid(PmkidCapturePlan plan, String prefix,
                                                       File capture) throws IOException {
        captureProcess = runner.start(prefix + plan.captureCommand(), plan.seconds, WifiSessionState::log);
        if (cancelled.get()) captureProcess.close();
        long deadline = SystemClock.elapsedRealtime() + plan.seconds * 1000L;
        long nextVerify = SystemClock.elapsedRealtime() + 5000;
        CaptureValidator.Verification verified = null;
        while (!cancelled.get() && captureProcess.running() && SystemClock.elapsedRealtime() < deadline) {
            stage(R.string.wifi_stage_pmkid);
            if (capture.length() > CaptureFiles.MAX_BYTES) {
                throw new IOException("Capture reached the 100 MB limit. The original is retained in the session folder.");
            }
            if (capture.length() > 24 && SystemClock.elapsedRealtime() >= nextVerify) {
                stage(R.string.wifi_stage_verifying);
                verified = new CaptureValidator(runner, paths).verify(capture, plan.bssid, plan.client);
                if (verified.hasPmkid()) break;
                nextVerify = SystemClock.elapsedRealtime() + 5000;
            }
            pause(400);
        }
        int code = captureProcess.result();
        captureProcess.close();
        String output = captureProcess.output();
        captureProcess = null;
        if (!cancelled.get() && code > 0 && code != 124 && code != 143 && code != 137) {
            throw new IOException(output.isEmpty() ? "PMKID helper failed (exit " + code + ")" : output);
        }
        // A successful process exit only means that collection ended. WPA*01 validation proves a PMKID.
        return verified;
    }

    private CaptureValidator.Verification collect(WifiSessionRequest request, String iface, String prefix,
                                                   File workspace, File capture) throws IOException {
        File csv = new File(workspace, "capture-01.csv");
        String output = paths.guest(new File(workspace, "capture"));
        String command = collectionCommand(request, iface, output);
        captureProcess = runner.start(prefix + command, request.seconds, WifiSessionState::log);
        if (cancelled.get()) captureProcess.close();
        long deadline = SystemClock.elapsedRealtime() + request.seconds * 1000L;
        long nextBurst = SystemClock.elapsedRealtime() + 5000;
        long nextVerify = SystemClock.elapsedRealtime() + 10000;
        long nextCsv = 0;
        CaptureValidator.Verification verified = null;
        while (!cancelled.get() && captureProcess.running() && SystemClock.elapsedRealtime() < deadline) {
            stage(request.mode == WifiSessionRequest.Mode.CAPTURE ? R.string.wifi_stage_listening : R.string.wifi_stage_scanning);
            long now = SystemClock.elapsedRealtime();
            if (now >= nextCsv) { readNetworks(csv); nextCsv = now + 2000; }
            if (request.mode == WifiSessionRequest.Mode.CAPTURE && request.assist && now >= nextBurst) {
                stage(R.string.wifi_stage_deauth);
                burst(request, iface, prefix, Math.min(8, (int) ((deadline - now) / 1000)));
                nextBurst = SystemClock.elapsedRealtime() + 10000;
            }
            if (capture != null && capture.length() > CaptureFiles.MAX_BYTES) {
                throw new IOException("Capture reached the 100 MB limit. The original is retained in the session folder.");
            }
            if (capture != null && capture.length() > 24 && now >= nextVerify && !cancelled.get()) {
                stage(R.string.wifi_stage_verifying);
                verified = new CaptureValidator(runner, paths).verify(capture, request.bssid, request.client);
                if (verified.usable()) break;
                nextVerify = SystemClock.elapsedRealtime() + 10000;
            }
            pause(400);
        }
        int code = captureProcess.result();
        captureProcess.close();
        String toolOutput = captureProcess.output();
        captureProcess = null;
        readNetworks(csv);
        if (!cancelled.get() && code > 0 && code != 124 && code != 143 && code != 137) {
            throw new IOException(toolOutput.isEmpty() ? "Capture tool failed (exit " + code + ")" : toolOutput);
        }
        return verified;
    }

    static String collectionCommand(WifiSessionRequest request, String iface, String output) {
        switch (request.mode) {
            case SCAN:
                return WifiCommands.scan(iface, output);
            case CLIENT_SCAN:
                return WifiCommands.clientScan(iface, output, request.bssid, request.channel);
            case CAPTURE:
                return WifiCommands.capture(iface, output, request.bssid, request.channel);
            default:
                throw new IllegalArgumentException("This operation does not collect Wi-Fi observations");
        }
    }

    static String scanResult(Context context, WifiSessionRequest request, List<WifiNetwork> networks) {
        if (request.mode == WifiSessionRequest.Mode.SCAN) {
            int count = networks.size();
            return context.getResources().getQuantityString(R.plurals.wifi_scan_finished, count, count);
        }
        if (request.mode != WifiSessionRequest.Mode.CLIENT_SCAN) {
            throw new IllegalArgumentException("This operation is not a Wi-Fi scan");
        }
        int count = 0;
        for (WifiNetwork network : networks) {
            if (network.bssid.equals(request.bssid)) {
                count = network.clients.size();
                break;
            }
        }
        return context.getResources().getQuantityString(R.plurals.wifi_client_scan_finished, count, count);
    }

    /** A new targeted scan must not report clients retained from an earlier session. */
    static List<WifiNetwork> resetClientObservations(List<WifiNetwork> networks, String bssid) {
        List<WifiNetwork> result = new ArrayList<>();
        for (WifiNetwork network : networks) {
            result.add(network.bssid.equals(bssid)
                    ? new WifiNetwork(network.bssid, network.ssid, network.channel, network.signal,
                            network.security, network.beacons, Collections.emptyList())
                    : network);
        }
        return result;
    }

    private void readNetworks(File csv) throws IOException {
        if (!csv.isFile()) return;
        List<WifiNetwork> latest = WifiScanParser.parse(WifiPaths.read(csv, 2 * 1024 * 1024));
        WifiSessionState.networks(WifiScanParser.merge(WifiSessionState.current().networks, latest));
    }

    private void deauth(WifiSessionRequest request, String iface, String prefix) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + request.seconds * 1000L;
        int retunes = 0;
        while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
            stage(R.string.wifi_stage_deauth);
            try {
                burst(request, iface, prefix, Math.min(8, (int) ((deadline - SystemClock.elapsedRealtime()) / 1000)));
            } catch (IOException e) {
                int channel = WifiCommands.reportedChannel(e.getMessage() == null ? "" : e.getMessage());
                if (channel == 0 || retunes++ >= 2) throw e;
                interfaces.setChannel(channel);
                WifiSessionState.log(context.getString(R.string.wifi_channel_changed, channel));
            }
            for (int i = 0; i < 6 && !cancelled.get() && SystemClock.elapsedRealtime() < deadline; i++) pause(500);
        }
    }

    private void burst(WifiSessionRequest request, String iface, String prefix, int seconds) throws IOException {
        if (seconds <= 0 || cancelled.get()) return;
        deauthProcess = runner.start(prefix + WifiCommands.deauth(iface, request.bssid, request.client, 5), seconds, WifiSessionState::log);
        while (deauthProcess.running() && !cancelled.get()) pause(100);
        int code = deauthProcess.result();
        deauthProcess.close();
        String output = deauthProcess.output();
        deauthProcess = null;
        if (!cancelled.get() && code != 0) {
            throw new IOException(output.isEmpty() ? context.getString(R.string.wifi_injection_failed) : output);
        }
    }

    private void stopProcesses() throws IOException {
        IOException failure = null;
        ChrootRunner.OwnedProcess deauth = deauthProcess, capture = captureProcess;
        try { if (deauth != null) deauth.close(); } catch (IOException e) { failure = e; }
        try { if (capture != null) capture.close(); } catch (IOException e) { failure = combine(failure, e); }
        if (failure != null) throw failure;
    }

    private void checkCancelled() throws Cancelled { if (cancelled.get()) throw new Cancelled(); }
    private static final class Cancelled extends IOException { }

    private static IOException asIOException(Exception error) {
        return error instanceof IOException ? (IOException) error : new IOException(error);
    }

    private static IOException combine(IOException first, IOException next) {
        if (first == null) return next;
        return new IOException(String.valueOf(first.getMessage()) + "\n" + String.valueOf(next.getMessage()), next);
    }

    private void pause(long millis) throws IOException {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); cancelled.set(true); throw new Cancelled(); }
    }
}
