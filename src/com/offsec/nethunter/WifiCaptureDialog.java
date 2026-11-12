package com.offsec.nethunter;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.wifi.WifiStore;
import com.offsec.nethunter.wifi.WifiViewModel;

import java.io.IOException;

/** A saved capture's details and explicit file actions, backed by the live library record. */
public class WifiCaptureDialog extends DialogFragment {
    public static final String TAG = "wifi_capture_details";
    private WifiViewModel model;
    private WifiStore.Capture capture;
    private boolean detailsExpanded;

    public static WifiCaptureDialog newInstance(WifiStore.Capture capture) {
        Bundle args = new Bundle();
        args.putString("id", capture.id);
        args.putString("name", capture.name);
        args.putString("validation", capture.validation);
        args.putString("details", capture.details);
        args.putLong("size", capture.size);
        args.putLong("created", capture.created);
        WifiCaptureDialog dialog = new WifiCaptureDialog();
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state) {
        AppCompatDialog dialog = new AppCompatDialog(requireContext());
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(R.string.wifi_capture_details);
        return dialog;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        return inflater.inflate(R.layout.wifi_capture_details, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        model = new ViewModelProvider(requireParentFragment()).get(WifiViewModel.class);
        detailsExpanded = state != null && state.getBoolean("details_expanded");
        Bundle args = requireArguments();
        render(view, args.getString("name", ""), args.getLong("size"), args.getLong("created"),
                args.getString("validation", "UNVERIFIED"), args.getString("details", ""));
        view.findViewById(R.id.wifi_capture_close).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.wifi_capture_details_toggle).setOnClickListener(v -> {
            detailsExpanded = !detailsExpanded;
            updateDetails(view);
        });
        view.findViewById(R.id.wifi_capture_validate).setOnClickListener(v -> { if (capture != null) model.validate(capture); });
        view.findViewById(R.id.wifi_capture_share).setOnClickListener(v -> share());
        view.findViewById(R.id.wifi_capture_rename).setOnClickListener(v -> {
            if (capture == null) return;
            WifiFragment.prompt(this, getString(R.string.wifi_rename), "", getString(R.string.wifi_name),
                    capture.name, false, value -> model.rename(capture, value));
        });
        view.findViewById(R.id.wifi_capture_delete).setOnClickListener(v -> {
            if (capture == null) return;
            WifiFragment.show(this, new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.wifi_delete)
                    .setMessage(getString(R.string.wifi_delete_question, capture.name))
                    .setPositiveButton(R.string.wifi_delete, (dialog, which) -> model.delete(capture))
                    .setNegativeButton(R.string.wifi_cancel, null).create());
        });
        view.findViewById(R.id.wifi_capture_submit).setOnClickListener(v -> {
            if (capture == null) return;
            WifiFragment.prompt(this, getString(R.string.wifi_submit),
                    getString(R.string.wifi_submit_description, capture.name, Formatter.formatShortFileSize(requireContext(), capture.size))
                            + "\n\n" + getString(R.string.wifi_email_help),
                    getString(R.string.wifi_email), model.email(), true, email -> {
                        model.email(email);
                        model.submit(capture.id, email);
                    });
        });
        model.captures.observe(getViewLifecycleOwner(), captures -> {
            capture = null;
            for (WifiStore.Capture item : captures) {
                if (item.id.equals(args.getString("id"))) { capture = item; break; }
            }
            if (capture == null) { dismiss(); return; }
            render(view, capture.name, capture.size, capture.created, capture.validation, capture.details);
            updateActions(view);
        });
        model.working.observe(getViewLifecycleOwner(), ignored -> updateActions(view));
        updateActions(view);
        updateDetails(view);
    }

    private void render(View view, String name, long size, long created, String validation, String details) {
        text(view, R.id.wifi_capture_name, name);
        text(view, R.id.wifi_capture_size, Formatter.formatShortFileSize(requireContext(), size));
        text(view, R.id.wifi_capture_date, WifiFragment.date(created));
        TextView status = view.findViewById(R.id.wifi_capture_state);
        status.setText(WifiFragment.captureState(requireContext(), validation));
        boolean verified = validation.equals("HANDSHAKE") || validation.equals("PMKID") || validation.equals("HANDSHAKE_AND_PMKID");
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(status,
                verified ? R.drawable.ic_wifi_selected : R.drawable.ic_wifi_capture, 0, 0, 0);
        TextViewCompat.setCompoundDrawableTintList(status, ContextCompat.getColorStateList(requireContext(),
                verified ? R.color.system_accent : R.color.system_tab_text));
        ((TextView) view.findViewById(R.id.wifi_capture_state_help)).setText(verified ? R.string.wifi_capture_verified_help
                : validation.equals("EMPTY") ? R.string.wifi_capture_empty_help : R.string.wifi_capture_unverified_help);
        text(view, R.id.wifi_capture_verification, details.isEmpty() ? getString(R.string.wifi_capture_no_details) : details);
    }

    private void updateActions(View view) {
        boolean working = Boolean.TRUE.equals(model.working.getValue());
        int[] actions = {R.id.wifi_capture_submit, R.id.wifi_capture_validate, R.id.wifi_capture_share,
                R.id.wifi_capture_rename, R.id.wifi_capture_delete};
        for (int id : actions) {
            view.findViewById(id).setEnabled(capture != null && !working);
            view.findViewById(id).setAlpha(capture != null && !working ? 1f : 0.45f);
        }
        view.findViewById(R.id.wifi_capture_action_busy).setVisibility(working ? View.VISIBLE : View.GONE);
    }

    private void updateDetails(View view) {
        view.findViewById(R.id.wifi_capture_verification).setVisibility(detailsExpanded ? View.VISIBLE : View.GONE);
        ((MaterialButton) view.findViewById(R.id.wifi_capture_details_toggle))
                .setIconResource(detailsExpanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
    }

    private void share() {
        if (capture == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".wifi.captures",
                    WifiStore.get(requireContext()).file(capture), capture.name);
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, capture.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri(capture.name, uri));
            startActivity(Intent.createChooser(share, getString(R.string.wifi_share)));
        } catch (IOException | RuntimeException e) { NhPaths.showMessage(requireContext(), e.getMessage()); }
    }

    @Override public void onStart() { super.onStart(); resizeWindow(); }

    @Override public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        resizeWindow();
    }

    private void resizeWindow() {
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(Math.min(metrics.widthPixels - dp(32), dp(560)),
                Math.min(metrics.heightPixels - dp(64), dp(760)));
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("details_expanded", detailsExpanded);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static void text(View view, int id, String value) { ((TextView) view.findViewById(id)).setText(value); }
}
