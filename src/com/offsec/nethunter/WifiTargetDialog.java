package com.offsec.nethunter;

import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.offsec.nethunter.wifi.WifiCommands;
import com.offsec.nethunter.wifi.WifiNetwork;
import com.offsec.nethunter.wifi.WifiSessionRequest;
import com.offsec.nethunter.wifi.WifiViewModel;

import java.util.ArrayList;
import java.util.List;

/** Target details and session options, kept together with a persistent action footer. */
public class WifiTargetDialog extends DialogFragment {
    public static final String TAG = "wifi_target";
    private WifiNetwork network;
    private String iface, selectedClient = "";
    private WifiSessionRequest.Mode actionMode = WifiSessionRequest.Mode.CAPTURE;
    private EditText channel, duration;
    private MaterialAutoCompleteTextView client;
    private MaterialCheckBox assist;
    private MaterialCardView captureCard, deauthCard, pmkidCard;
    private MaterialButton start;
    private TextView modeHelp, footerClient;
    private WifiViewModel model;

    public static WifiTargetDialog newInstance(WifiNetwork network, String iface) {
        Bundle args = new Bundle();
        args.putString("iface", iface);
        args.putString("bssid", network.bssid);
        args.putString("ssid", network.ssid);
        args.putString("security", network.security);
        args.putInt("channel", network.channel);
        args.putInt("signal", network.signal);
        args.putLong("beacons", network.beacons);
        args.putStringArrayList("clients", new ArrayList<>(network.clients));
        WifiTargetDialog dialog = new WifiTargetDialog();
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state) {
        Bundle args = requireArguments();
        iface = args.getString("iface");
        network = new WifiNetwork(args.getString("bssid"), args.getString("ssid"), args.getInt("channel"),
                args.getInt("signal"), args.getString("security"), args.getLong("beacons"), args.getStringArrayList("clients"));
        model = new ViewModelProvider(requireParentFragment()).get(WifiViewModel.class);
        if (state != null) {
            actionMode = state.getBoolean("capture", true) ? WifiSessionRequest.Mode.CAPTURE : WifiSessionRequest.Mode.DEAUTH;
            String savedMode = state.getString("action_mode", actionMode.name());
            if (savedMode.equals(WifiSessionRequest.Mode.PMKID_CAPTURE.name())) actionMode = WifiSessionRequest.Mode.PMKID_CAPTURE;
            else if (savedMode.equals(WifiSessionRequest.Mode.DEAUTH.name())) actionMode = WifiSessionRequest.Mode.DEAUTH;
        }
        selectedClient = state == null ? "" : state.getString("client", "");
        AppCompatDialog dialog = new AppCompatDialog(requireContext());
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.wifi_session_options);
        dialog.setTitle(network.ssid.isEmpty() ? getString(R.string.wifi_hidden_network) : network.ssid);

        String ssid = network.ssid.isEmpty() ? getString(R.string.wifi_hidden_network) : network.ssid;
        text(dialog, R.id.wifi_target_ssid, ssid);
        text(dialog, R.id.wifi_target_bssid, network.bssid);
        text(dialog, R.id.wifi_target_security, network.security);
        text(dialog, R.id.wifi_target_channel_metric, getString(R.string.wifi_target_channel_value, network.channel));
        text(dialog, R.id.wifi_target_signal_metric, getString(R.string.wifi_target_signal_value, network.signal));
        text(dialog, R.id.wifi_target_clients_metric, getResources().getQuantityString(R.plurals.wifi_client_count, network.clients.size(), network.clients.size()));
        text(dialog, R.id.wifi_target_adapter, getString(R.string.wifi_target_adapter, iface));
        text(dialog, R.id.wifi_target_footer_network, ssid);
        text(dialog, R.id.wifi_target_client_help, network.clients.isEmpty() ? getString(R.string.wifi_target_client_empty)
                : getResources().getQuantityString(R.plurals.wifi_target_client_help, network.clients.size(), network.clients.size()));

        channel = dialog.findViewById(R.id.wifi_target_channel);
        duration = dialog.findViewById(R.id.wifi_target_duration);
        assist = dialog.findViewById(R.id.wifi_target_assist);
        client = dialog.findViewById(R.id.wifi_target_client);
        captureCard = dialog.findViewById(R.id.wifi_target_capture_mode);
        deauthCard = dialog.findViewById(R.id.wifi_target_deauth_mode);
        pmkidCard = dialog.findViewById(R.id.wifi_target_pmkid_mode);
        start = dialog.findViewById(R.id.wifi_target_start);
        modeHelp = dialog.findViewById(R.id.wifi_target_mode_help);
        footerClient = dialog.findViewById(R.id.wifi_target_footer_client);
        channel.setText(state == null ? String.valueOf(network.channel) : state.getString("channel"));
        duration.setText(state == null ? "120" : state.getString("duration"));
        assist.setChecked(state != null && state.getBoolean("assist"));

        List<String> choices = new ArrayList<>();
        choices.add(getString(R.string.wifi_all_clients_short));
        choices.addAll(network.clients);
        client.setSimpleItems(choices.toArray(new String[0]));
        selectClient(network.clients.indexOf(selectedClient) + 1);
        client.setOnItemClickListener((parent, view, position, id) -> selectClient(position));
        captureCard.setEnabled(network.supportsWpaCapture());
        captureCard.setAlpha(network.supportsWpaCapture() ? 1f : 0.5f);
        captureCard.setOnClickListener(v -> selectMode(WifiSessionRequest.Mode.CAPTURE));
        deauthCard.setOnClickListener(v -> selectMode(WifiSessionRequest.Mode.DEAUTH));
        pmkidCard.setEnabled(network.supportsPmkidCapture());
        pmkidCard.setAlpha(network.supportsPmkidCapture() ? 1f : 0.5f);
        pmkidCard.setOnClickListener(v -> selectMode(WifiSessionRequest.Mode.PMKID_CAPTURE));
        dialog.findViewById(R.id.wifi_target_cancel).setOnClickListener(v -> dismiss());
        MaterialButton discoverClients = dialog.findViewById(R.id.wifi_target_scan_clients);
        discoverClients.setText(getString(R.string.wifi_discover_clients, WifiSessionRequest.DEFAULT_SCAN_SECONDS));
        discoverClients.setOnClickListener(v -> discoverClients());
        updateMode();
        start.setOnClickListener(v -> startSession());
        return dialog;
    }

    private void selectClient(int position) {
        selectedClient = position > 0 && position <= network.clients.size() ? network.clients.get(position - 1) : "";
        client.setText(selectedClient.isEmpty() ? getString(R.string.wifi_all_clients_short) : selectedClient, false);
        footerClient.setText(actionMode == WifiSessionRequest.Mode.PMKID_CAPTURE ? getString(R.string.wifi_clientless)
                : selectedClient.isEmpty() ? getString(R.string.wifi_all_clients)
                : getString(R.string.wifi_target_client_selected, selectedClient));
    }

    private void selectMode(WifiSessionRequest.Mode mode) {
        if (actionMode == mode) return;
        String previousDefault = actionMode == WifiSessionRequest.Mode.DEAUTH ? "30" : "120";
        actionMode = mode;
        if (duration.getText().toString().equals(previousDefault)) {
            duration.setText(mode == WifiSessionRequest.Mode.DEAUTH ? "30" : "120");
        }
        updateMode();
    }

    private void updateMode() {
        int accent = ContextCompat.getColor(requireContext(), R.color.system_accent);
        int outline = ContextCompat.getColor(requireContext(), R.color.md_theme_dark_outlineVariant);
        boolean capture = actionMode == WifiSessionRequest.Mode.CAPTURE;
        boolean deauth = actionMode == WifiSessionRequest.Mode.DEAUTH;
        boolean pmkid = actionMode == WifiSessionRequest.Mode.PMKID_CAPTURE;
        captureCard.setChecked(capture);
        deauthCard.setChecked(deauth);
        pmkidCard.setChecked(pmkid);
        captureCard.setStrokeColor(capture ? accent : outline);
        deauthCard.setStrokeColor(deauth ? accent : outline);
        pmkidCard.setStrokeColor(pmkid ? accent : outline);
        modeHelp.setText(pmkid ? (network.supportsPmkidCapture() ? R.string.wifi_pmkid_mode_help : R.string.wifi_unsupported_pmkid)
                : capture ? (network.supportsWpaCapture() ? R.string.wifi_capture_mode_help : R.string.wifi_unsupported_capture)
                : R.string.wifi_deauth_mode_help);
        assist.setVisibility(capture ? View.VISIBLE : View.GONE);
        getDialogView(R.id.wifi_target_client_field).setVisibility(pmkid ? View.GONE : View.VISIBLE);
        getDialogView(R.id.wifi_target_client_help).setVisibility(pmkid ? View.GONE : View.VISIBLE);
        getDialogView(R.id.wifi_target_scan_clients).setVisibility(pmkid ? View.GONE : View.VISIBLE);
        footerClient.setText(pmkid ? getString(R.string.wifi_clientless) : selectedClient.isEmpty()
                ? getString(R.string.wifi_all_clients) : getString(R.string.wifi_target_client_selected, selectedClient));
        start.setText(pmkid ? R.string.wifi_start_pmkid : capture ? R.string.wifi_start_capture : R.string.wifi_start_deauth);
        start.setEnabled(deauth || (pmkid ? network.supportsPmkidCapture() : network.supportsWpaCapture()));
    }

    private void startSession() {
        int ch, seconds;
        try { ch = WifiCommands.channel(Integer.parseInt(channel.getText().toString())); }
        catch (IllegalArgumentException e) { channel.setError(getString(R.string.wifi_invalid_channel)); channel.requestFocus(); return; }
        try { seconds = WifiCommands.duration(Integer.parseInt(duration.getText().toString())); }
        catch (IllegalArgumentException e) { duration.setError(getString(R.string.wifi_invalid_duration)); duration.requestFocus(); return; }
        if (model.start(new WifiSessionRequest(actionMode, iface, network.bssid,
                actionMode == WifiSessionRequest.Mode.PMKID_CAPTURE ? "" : selectedClient, network.ssid, ch, seconds,
                actionMode == WifiSessionRequest.Mode.CAPTURE && assist.isChecked()))) {
            startActivity(WifiSessionActivity.intent(requireContext()));
            dismiss();
        }
    }

    private void discoverClients() {
        int ch;
        try { ch = WifiCommands.channel(Integer.parseInt(channel.getText().toString())); }
        catch (IllegalArgumentException e) { channel.setError(getString(R.string.wifi_invalid_channel)); channel.requestFocus(); return; }
        if (model.start(new WifiSessionRequest(WifiSessionRequest.Mode.CLIENT_SCAN,
                iface, network.bssid, "", network.ssid, ch, WifiSessionRequest.DEFAULT_SCAN_SECONDS, false))) {
            dismiss();
        }
    }

    @Override public void onStart() {
        super.onStart();
        resizeWindow();
    }

    @Override public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // AppNavHomeActivity handles orientation itself, so its dialogs also resize here.
        resizeWindow();
    }

    private void resizeWindow() {
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = Math.min(metrics.widthPixels - dp(32), dp(560));
        int height = Math.min(metrics.heightPixels - dp(64), dp(760));
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(width, height);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("capture", actionMode != WifiSessionRequest.Mode.DEAUTH);
        state.putString("action_mode", actionMode.name());
        state.putString("client", selectedClient);
        if (channel != null) {
            state.putString("channel", channel.getText().toString());
            state.putString("duration", duration.getText().toString());
            state.putBoolean("assist", assist.isChecked());
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private View getDialogView(int id) { return client.getRootView().findViewById(id); }
    private static void text(Dialog dialog, int id, String value) { ((TextView) dialog.findViewById(id)).setText(value); }
}
