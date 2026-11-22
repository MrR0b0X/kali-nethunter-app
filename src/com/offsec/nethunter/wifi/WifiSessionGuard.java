package com.offsec.nethunter.wifi;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.offsec.nethunter.R;

/** Used by legacy launchers that can change the radio or stop the chroot. */
public final class WifiSessionGuard {
    private WifiSessionGuard() { }

    public static boolean allowMutation(Context context) {
        boolean active = WifiSessionState.active();
        boolean recovery = context.getSharedPreferences("wifi_radio_recovery", Context.MODE_PRIVATE).contains("original");
        if (!active && !recovery) return true;
        Context application = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(application, active ? R.string.wifi_busy : R.string.wifi_recovery_needed, Toast.LENGTH_LONG).show());
        return false;
    }

    /** Caller must close the reservation in a finally block after all radio work ends. */
    public static WifiSessionState.LegacyOperation reserveMutation(Context context) {
        if (!allowMutation(context)) return null;
        WifiSessionState.LegacyOperation operation = WifiSessionState.reserveLegacyOperation();
        if (operation == null) {
            Context application = context.getApplicationContext();
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(application, R.string.wifi_busy, Toast.LENGTH_LONG).show());
        }
        return operation;
    }
}
