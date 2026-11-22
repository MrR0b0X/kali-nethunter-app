package com.offsec.nethunter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.offsec.nethunter.wifi.WifiSessionState;

/** Shared live log tab for the module and the dedicated session screen. */
public class WifiLogFragment extends Fragment {
    public static WifiLogFragment forSession(long generation) {
        WifiLogFragment fragment = new WifiLogFragment();
        Bundle args = new Bundle();
        args.putLong("generation", generation);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        return inflater.inflate(R.layout.wifi_logs, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        TextView output = view.findViewById(R.id.wifi_log_output);
        TextView status = view.findViewById(R.id.wifi_log_status);
        NestedScrollView scroll = view.findViewById(R.id.wifi_log_scroll);
        MaterialCheckBox follow = view.findViewById(R.id.wifi_log_follow);
        follow.setChecked(state == null || state.getBoolean("follow", true));
        follow.setOnCheckedChangeListener((button, checked) -> { if (checked) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); });
        scroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, x, y, oldX, oldY) -> {
            if (y < oldY) follow.setChecked(false);
        });
        WifiSessionState.live().observe(getViewLifecycleOwner(), session -> {
            long generation = getArguments() == null ? -1 : requireArguments().getLong("generation", -1);
            boolean available = generation < 0 || generation == session.generation;
            status.setText(!available ? getString(R.string.wifi_session_unavailable)
                    : session.status.isEmpty() ? getString(R.string.wifi_idle) : session.status);
            String log = !available || session.log.isEmpty() ? getString(R.string.wifi_log_empty) : session.log;
            if (!log.contentEquals(output.getText())) {
                output.setText(log);
                if (follow.isChecked()) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (getView() != null) state.putBoolean("follow", ((MaterialCheckBox) getView().findViewById(R.id.wifi_log_follow)).isChecked());
    }
}
