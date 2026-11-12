package com.offsec.nethunter.wifi;

import android.content.Context;

import com.offsec.nethunter.utils.NhPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bounded probes plus independently owned process groups for radio operations. */
public class ChrootRunner {
    private final String chroot;
    private final String busybox;
    private final String hostPath;

    public interface LineListener { void line(String line); }

    public ChrootRunner(Context context) {
        NhPaths.getInstance(context);
        chroot = NhPaths.CHROOT_PATH();
        busybox = NhPaths.getBusyboxPath();
        hostPath = NhPaths.APP_SCRIPTS_BIN_PATH;
    }

    private String inside(String script) {
        String launcher = busybox == null || busybox.isEmpty() ? "chroot" : WifiCommands.quote(busybox) + " chroot";
        return "exec " + launcher + " " + WifiCommands.quote(chroot)
                + " /usr/bin/env -i HOME=/root LANG=C LC_ALL=C TERM=dumb "
                + "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
                + "/bin/bash -c " + WifiCommands.quote(script);
    }

    public Result run(String command, int seconds) throws IOException {
        return root(inside("exec timeout --signal=TERM --kill-after=2 " + seconds
                + " /bin/bash -c " + WifiCommands.quote(command)), seconds + 5);
    }

    public String require(String command, int seconds) throws IOException {
        Result result = run(command, seconds);
        if (result.code != 0) throw new IOException(result.output.isEmpty()
                ? "Kali command failed (" + result.code + ")" : result.output);
        return result.output;
    }

    public Result host(String command, int seconds) throws IOException {
        String timeout = busybox.isEmpty() ? "timeout" : WifiCommands.quote(busybox) + " timeout";
        return root(timeout + " -s TERM -k 2 " + seconds + " /system/bin/sh -c "
                + WifiCommands.quote(command), seconds + 4);
    }

    private Result root(String script, int seconds) throws IOException {
        Process process = launch(script);
        LogBuffer output = new LogBuffer();
        Thread reader = read(process, output, null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            while (exitCode(process) == null && System.nanoTime() < deadline) Thread.sleep(50);
            Integer code = exitCode(process);
            if (code == null) {
                process.destroy();
                throw new IOException("Root/Kali command timed out");
            }
            reader.join(1000);
            return new Result(code, output.text());
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted", e);
        } finally {
            process.destroy();
        }
    }

    private Process launch(String script) throws IOException {
        return new ProcessBuilder("su", "-mm", "-c",
                "export PATH=" + WifiCommands.quote(hostPath) + ":$PATH; " + script)
                .redirectErrorStream(true).start();
    }

    public OwnedProcess start(String command, int seconds, LineListener listener) throws IOException {
        return new OwnedProcess(command, seconds, listener);
    }

    /** Recover only groups carrying our exact random marker; never kill by tool name. */
    public void recoverOwnedProcesses() throws IOException {
        require(stopFunction() + "failed=0; for d in /tmp/nethunter-wifi-*; do "
                + "[ -d \"$d\" ] && [ ! -L \"$d\" ] || continue; token=$(basename -- \"$d\"); "
                + "case \"$token\" in nethunter-wifi-????????-????-????-????-????????????) ;; *) continue;; esac; "
                + "stop_owned \"$d\" \"$token\" || failed=1; done; exit \"$failed\"", 20);
    }

    /** The supervisor stays alive until TERM has had a grace period, then kills its group.
     * This also covers app death and tools whose descendants ignore TERM. */
    static String supervisor(String command, int seconds, String directory, String token) {
        String stop = "trap '' TERM HUP INT; kill -TERM -- -$$ 2>/dev/null; sleep 2; kill -KILL -- -$$";
        String body = "umask 077; mkdir " + WifiCommands.quote(directory)
                + " || exit 1; echo $$ > " + WifiCommands.quote(directory + "/pid")
                + "; trap " + WifiCommands.quote(stop) + " TERM HUP INT; "
                + "printf '%s\\n' " + WifiCommands.quote(token + ":ready") + "; "
                + "timeout --foreground --signal=TERM --kill-after=2 " + seconds
                + " /bin/bash -c " + WifiCommands.quote(command) + "; code=$?; "
                + "printf '%s%s\\n' " + WifiCommands.quote(token + ":exit:") + " \"$code\"; " + stop;
        return "exec setsid --wait /bin/bash -c " + WifiCommands.quote(body) + " " + WifiCommands.quote(token);
    }

    // Root-only pid records plus an exact argv token prevent PID reuse from killing another job.
    static String stopFunction() {
        return "owned_marker() { tr '\\000' '\\n' < /proc/\"$pid\"/cmdline 2>/dev/null "
                + "| grep -Fxq -- \"$token\"; }; "
                + "live_group() { local processes; processes=$(ps -e -o pgid=,stat=) || return 0; "
                + "printf '%s\\n' \"$processes\" | awk -v g=\"$pid\" "
                + "'$1 == g && $2 !~ /^Z/ {live=1} END {exit !live}'; }; "
                + "stop_owned() { local d=\"$1\" token=\"$2\" pid; "
                + "pid=$(cat \"$d/pid\" 2>/dev/null); "
                + "case \"$pid\" in ''|*[!0-9]*) return 0;; esac; [ \"$pid\" -gt 1 ] || return 1; "
                + "if owned_marker; then kill -TERM -- -\"$pid\" 2>/dev/null; "
                + "for ((i=0;i<40;i++)); do live_group || break; sleep 0.1; done; "
                + "if owned_marker; then kill -KILL -- -\"$pid\" 2>/dev/null; sleep 0.2; fi; fi; "
                + "if live_group; then printf 'Session processes still stopping: %s\\n' \"$token\"; return 1; fi; "
                + "rm -rf -- \"$d\"; }; ";
    }

    public final class OwnedProcess implements AutoCloseable {
        private final String token = "nethunter-wifi-" + UUID.randomUUID();
        private final String directory = "/tmp/" + token;
        private final LogBuffer output = new LogBuffer();
        private final CountDownLatch ready = new CountDownLatch(1);
        private boolean closed;
        private volatile Integer commandResult;
        private final Process process;
        private final Thread reader;

        OwnedProcess(String command, int seconds, LineListener listener) throws IOException {
            if (seconds < 1 || seconds > 620) throw new IllegalArgumentException("Invalid process deadline");
            process = launch(inside(supervisor(command, seconds, directory, token)));
            reader = read(process, output, line -> {
                if (line.equals(token + ":ready")) ready.countDown();
                else if (line.startsWith(token + ":exit:")) {
                    try { commandResult = Integer.parseInt(line.substring((token + ":exit:").length())); }
                    catch (NumberFormatException ignored) { }
                }
                else if (listener != null) listener.line(line);
            });
        }

        public boolean running() { return exitCode(process) == null; }
        public String output() {
            return output.text().replaceAll("(?m)^" + Pattern.quote(token) + ":(?:ready|exit:[0-9]+)\\s*$", "").trim();
        }
        public int result() {
            Integer code = exitCode(process);
            if (code != null) {
                try { reader.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return commandResult != null ? commandResult : (code == null ? -1 : code);
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            try {
                if (!ready.await(3, TimeUnit.SECONDS) && running()) {
                    throw new IOException("Session supervisor did not respond; its deadline still applies");
                }
                require(stopFunction() + "stop_owned " + WifiCommands.quote(directory)
                        + " " + WifiCommands.quote(token), 8);
                closed = true;
            } catch (IOException e) {
                throw new IOException("Could not confirm that session processes stopped; their time limit still applies. "
                        + "Use Restore interrupted session. " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping session processes", e);
            } finally {
                if (closed) process.destroy();
                try { reader.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static Thread read(Process process, LogBuffer output, LineListener listener) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] chars = new char[2048];
                StringBuilder line = new StringBuilder();
                int count;
                while ((count = in.read(chars)) != -1) {
                    for (int i = 0; i < count; i++) {
                        char c = chars[i];
                        if (c == '\n' || c == '\r' || line.length() >= 4096) {
                            if (line.length() > 0) {
                                String text = line.toString().replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "");
                                output.append(text);
                                if (listener != null) listener.line(text);
                                line.setLength(0);
                            }
                        } else line.append(c);
                    }
                }
                if (line.length() > 0) {
                    output.append(line.toString());
                    if (listener != null) listener.line(line.toString());
                }
            } catch (IOException ignored) { }
        }, "wifi-command-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static Integer exitCode(Process process) {
        try { return process.exitValue(); } catch (IllegalThreadStateException ignored) { return null; }
    }

    private static final class LogBuffer {
        private final StringBuilder value = new StringBuilder();
        synchronized void append(String line) {
            value.append(line).append('\n');
            if (value.length() > 32768) value.delete(0, value.length() - 32768);
        }
        synchronized String text() { return value.toString().trim(); }
    }

    public static final class Result {
        public final int code;
        public final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }
}
