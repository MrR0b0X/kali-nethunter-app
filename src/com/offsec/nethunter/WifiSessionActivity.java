package com.offsec.nethunter;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayoutMediator;
import com.offsec.nethunter.service.WifiSessionService;
import com.offsec.nethunter.wifi.WifiSessionRequest;
import com.offsec.nethunter.wifi.WifiSessionState;

/** Observes a reserved session. Opening, rotating, or closing this screen never starts radio work. */
public class WifiSessionActivity extends AppCompatActivity {
    private static final String GENERATION = "session_generation";
    private long generation;
    private WifiSessionRequest request;
    private ViewPager2 pager;
    private TabLayoutMediator mediator;

    public static Intent intent(Context context) {
        WifiSessionState.Snapshot session = WifiSessionState.current();
        Intent intent = new Intent(context, WifiSessionActivity.class)
                .putExtra(GENERATION, session.generation)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return session.request == null ? intent : session.request.write(intent);
    }

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        generation = getIntent().getLongExtra(GENERATION, -1);
        try { request = WifiSessionRequest.read(getIntent()); }
        catch (IllegalArgumentException | NullPointerException ignored) { request = null; }
        setContentView(R.layout.wifi_session_activity);
        if (getResources().getConfiguration().screenHeightDp < 480) {
            // The current stage is also in each tab; reserve short windows for their content.
            findViewById(R.id.wifi_session_footer_status).setVisibility(View.GONE);
            View footer = findViewById(R.id.wifi_session_footer);
            int horizontal = Math.round(16 * getResources().getDisplayMetrics().density);
            int vertical = Math.round(4 * getResources().getDisplayMetrics().density);
            footer.setPadding(horizontal, vertical, horizontal, vertical);
        }
        setTitle(request != null && request.mode == WifiSessionRequest.Mode.PMKID_CAPTURE ? R.string.wifi_pmkid_session
                : request != null && request.mode == WifiSessionRequest.Mode.DEAUTH
                ? R.string.wifi_deauth_session : R.string.wifi_capture_session);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            if (request != null && getResources().getConfiguration().fontScale <= 1.3f) {
                getSupportActionBar().setSubtitle(request.ssid.isEmpty() ? request.bssid : request.ssid);
            }
        }
        pager = findViewById(R.id.wifi_session_pager);
        pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull @Override public Fragment createFragment(int position) {
                return position == 0 ? new ProgressTab() : WifiLogFragment.forSession(generation);
            }
            @Override public int getItemCount() { return 2; }
        });
        mediator = new TabLayoutMediator(findViewById(R.id.wifi_session_tabs), pager,
                (tab, position) -> tab.setText(position == 0 ? R.string.wifi_progress : R.string.wifi_logs));
        mediator.attach();
        findViewById(R.id.wifi_session_back).setOnClickListener(v -> returnToModule(-1));
        findViewById(R.id.wifi_session_captures).setOnClickListener(v -> returnToModule(1));
        findViewById(R.id.wifi_session_stop).setOnClickListener(v -> {
            WifiSessionService.stop(this, generation);
            renderControls(WifiSessionState.current());
        });
        WifiSessionState.live().observe(this, this::renderControls);
    }

    private boolean matches(WifiSessionState.Snapshot session) {
        return request != null && session.generation == generation && session.request != null;
    }

    private void renderControls(WifiSessionState.Snapshot session) {
        boolean available = matches(session);
        boolean active = available && session.active;
        if (available && !active) WifiSessionState.markCompletedSessionViewed(generation);
        TextView status = findViewById(R.id.wifi_session_footer_status);
        status.setText(!available ? R.string.wifi_session_unavailable : active
                ? (session.stopRequested ? R.string.wifi_stage_stopping : R.string.wifi_session_running)
                : (session.error ? R.string.wifi_session_failed : R.string.wifi_session_finished));
        MaterialButton stop = findViewById(R.id.wifi_session_stop);
        stop.setVisibility(active ? View.VISIBLE : View.GONE);
        stop.setEnabled(active && session.cancellable && !session.stopRequested);
        stop.setAlpha(stop.isEnabled() ? 1f : 0.45f);
        stop.setText(session.stopRequested ? R.string.wifi_stage_stopping : R.string.wifi_stop);
        ((MaterialButton) findViewById(R.id.wifi_session_back)).setText(active
                ? R.string.wifi_session_background : R.string.wifi_session_back);
        findViewById(R.id.wifi_session_captures).setVisibility(available && !active && !session.captureId.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void returnToModule(int tab) {
        WifiSessionState.markCompletedSessionViewed(generation);
        Intent intent = new Intent(this, AppNavHomeActivity.class).putExtra("open_wifi", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (tab >= 0) intent.putExtra("wifi_tab", tab);
        startActivity(intent);
        finish();
    }

    @Override public boolean onSupportNavigateUp() { returnToModule(-1); return true; }

    @Override protected void onStop() {
        WifiSessionState.markCompletedSessionViewed(generation);
        super.onStop();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getLongExtra(GENERATION, -1) != generation) { setIntent(intent); recreate(); }
    }

    @Override protected void onDestroy() {
        if (mediator != null) mediator.detach();
        if (pager != null) pager.setAdapter(null);
        super.onDestroy();
    }

    public static class ProgressTab extends Fragment {
        @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
            return inflater.inflate(R.layout.wifi_session_progress, container, false);
        }

        @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
            WifiSessionActivity activity = (WifiSessionActivity) requireActivity();
            WifiSessionRequest request = activity.request;
            view.findViewById(R.id.wifi_session_target).setVisibility(request == null ? View.GONE : View.VISIBLE);
            if (request != null) {
                text(view, R.id.wifi_session_ssid, request.ssid.isEmpty() ? getString(R.string.wifi_hidden_network) : request.ssid);
                text(view, R.id.wifi_session_bssid, request.bssid);
                text(view, R.id.wifi_session_client, request.mode == WifiSessionRequest.Mode.PMKID_CAPTURE
                        ? getString(R.string.wifi_clientless) : request.client.isEmpty() ? getString(R.string.wifi_all_clients) : request.client);
                text(view, R.id.wifi_session_adapter, getString(R.string.wifi_session_adapter_channel, request.iface, request.channel));
                ((TextView) view.findViewById(R.id.wifi_session_mode)).setText(request.mode == WifiSessionRequest.Mode.PMKID_CAPTURE
                        ? R.string.wifi_pmkid_mode : request.mode == WifiSessionRequest.Mode.DEAUTH
                        ? R.string.wifi_deauth_action : request.assist ? R.string.wifi_session_assisted : R.string.wifi_session_passive);
                ((TextView) view.findViewById(R.id.wifi_session_limit_label)).setText(request.mode == WifiSessionRequest.Mode.DEAUTH
                        ? R.string.wifi_deauth_limit : R.string.wifi_session_limit);
                text(view, R.id.wifi_session_limit, getString(R.string.wifi_time_seconds, request.seconds));
            }
            WifiSessionState.live().observe(getViewLifecycleOwner(), session -> {
                boolean available = activity.matches(session);
                boolean active = available && session.active;
                TextView phase = view.findViewById(R.id.wifi_session_phase);
                phase.setText(!available ? getString(R.string.wifi_session_unavailable_help)
                        : session.status.isEmpty() ? getString(R.string.wifi_stage_preparing) : session.status);
                phase.setTextColor(ContextCompat.getColor(requireContext(), available && session.error
                        ? R.color.light_red : R.color.md_theme_dark_onSurface));
                text(view, R.id.wifi_session_elapsed, DateUtils.formatElapsedTime(available ? session.elapsed : 0));
                view.findViewById(R.id.wifi_session_busy).setVisibility(active ? View.VISIBLE : View.GONE);
                view.findViewById(R.id.wifi_session_help).setVisibility(active ? View.VISIBLE : View.GONE);
                TextView result = view.findViewById(R.id.wifi_session_result);
                result.setVisibility(available && !active && !session.captureId.isEmpty() ? View.VISIBLE : View.GONE);
                if (available) result.setText(WifiFragment.captureState(requireContext(), session.captureValidation));
            });
        }

        private static void text(View view, int id, String value) { ((TextView) view.findViewById(id)).setText(value); }
    }
}
