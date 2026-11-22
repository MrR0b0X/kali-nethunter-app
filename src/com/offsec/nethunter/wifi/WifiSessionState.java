package com.offsec.nethunter.wifi;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Application state observed by views; no Activity, Fragment, or View is retained. */
public final class WifiSessionState {
    private static final MutableLiveData<Snapshot> LIVE = new MutableLiveData<>(new Snapshot());
    private static Snapshot current = new Snapshot();
    private static long lastLogPublish;
    // Restored Activity intents must not match a new process's first session.
    private static long generation = System.currentTimeMillis();
    private static int legacyOperations;

    private WifiSessionState() { }
    public static LiveData<Snapshot> live() { return LIVE; }
    public static synchronized boolean active() { return current.active; }
    public static synchronized Snapshot current() { return new Snapshot(current); }

    public static synchronized boolean begin(String iface) {
        return begin(iface, true);
    }

    public static synchronized boolean begin(String iface, boolean cancellable) {
        return begin(iface, cancellable, null);
    }

    public static synchronized boolean begin(WifiSessionRequest request) {
        return begin(request.iface, true, request);
    }

    private static boolean begin(String iface, boolean cancellable, WifiSessionRequest request) {
        if (current.active || legacyOperations > 0) return false;
        List<WifiNetwork> networks = current.networks;
        current = new Snapshot();
        current.networks = networks;
        current.iface = iface;
        current.request = request;
        current.generation = ++generation;
        current.active = true;
        current.cancellable = cancellable;
        publish();
        return true;
    }

    /** Reserve before dispatching a legacy scan, including its adapter preparation. */
    static synchronized LegacyOperation reserveLegacyOperation() {
        if (current.active) return null;
        legacyOperations++;
        return new LegacyOperation();
    }

    public static final class LegacyOperation implements AutoCloseable {
        private boolean closed;
        private LegacyOperation() { }

        @Override public void close() {
            synchronized (WifiSessionState.class) {
                if (closed) return;
                closed = true;
                legacyOperations--;
            }
        }
    }

    public static synchronized void requestStop() {
        current.stopRequested = true;
        publish();
    }

    /** A screen for an older session must never stop a newer session. */
    public static synchronized boolean requestStop(long expectedGeneration) {
        if (!current.active || !current.cancellable || current.stopRequested || current.generation != expectedGeneration) return false;
        requestStop();
        return true;
    }

    /** Viewing a finished session hides its shortcut without affecting a newer session. */
    public static synchronized boolean markCompletedSessionViewed(long expectedGeneration) {
        if (current.active || current.generation != expectedGeneration || current.completedSessionViewed
                || current.request == null || !current.request.hasSessionScreen()) return false;
        current.completedSessionViewed = true;
        publish();
        return true;
    }

    public static synchronized void captureSaved(String id, String validation) {
        current.captureId = id;
        current.captureValidation = validation;
        publish();
    }

    public static synchronized void stage(String status, int elapsed) {
        status = status == null ? "" : status;
        if (current.status.equals(status) && current.elapsed == elapsed) return;
        current.status = status;
        current.elapsed = elapsed;
        publish();
    }

    public static synchronized void networks(List<WifiNetwork> networks) {
        current.networks = Collections.unmodifiableList(new ArrayList<>(networks));
        publish();
    }

    public static synchronized void log(String line) {
        if (line == null || line.isEmpty()) return;
        if (line.length() > 1024) line = line.substring(0, 1024);
        current.log += line + "\n";
        if (current.log.length() > 16000) current.log = current.log.substring(current.log.length() - 16000);
        long now = System.nanoTime();
        if (now - lastLogPublish > 500_000_000L) { lastLogPublish = now; publish(); }
    }

    public static synchronized void finish(String message, boolean error) {
        current.active = false;
        current.status = message == null ? "" : message;
        current.error = error;
        publish();
    }

    private static void publish() { LIVE.postValue(new Snapshot(current)); }

    public static final class Snapshot {
        public boolean active, error, stopRequested, cancellable = true;
        public boolean completedSessionViewed;
        public long generation;
        public String status = "", iface = "", log = "";
        public String captureId = "", captureValidation = "";
        public WifiSessionRequest request;
        public int elapsed;
        public List<WifiNetwork> networks = Collections.emptyList();
        Snapshot() { }
        Snapshot(Snapshot state) {
            active = state.active; error = state.error; status = state.status;
            generation = state.generation; stopRequested = state.stopRequested;
            cancellable = state.cancellable;
            completedSessionViewed = state.completedSessionViewed;
            request = state.request; captureId = state.captureId; captureValidation = state.captureValidation;
            iface = state.iface; log = state.log; elapsed = state.elapsed; networks = state.networks;
        }
    }
}
