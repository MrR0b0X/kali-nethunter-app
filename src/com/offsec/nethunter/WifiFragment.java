package com.offsec.nethunter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.Formatter;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.offsec.nethunter.service.WifiSessionService;
import com.offsec.nethunter.utils.NhPaths;
import com.offsec.nethunter.wifi.OnlineHashCrackClient;
import com.offsec.nethunter.wifi.WifiNetwork;
import com.offsec.nethunter.wifi.WifiSessionRequest;
import com.offsec.nethunter.wifi.WifiSessionState;
import com.offsec.nethunter.wifi.WifiStore;
import com.offsec.nethunter.wifi.WifiViewModel;
import com.offsec.nethunter.wifi.WirelessInterface;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/** NetHunter UI only; lifecycle-independent services own captures and uploads. */
public class WifiFragment extends Fragment {
    private WifiViewModel model;
    private TabLayoutMediator mediator;
    private ViewPager2 pager;

    public static WifiFragment newInstance(int sectionNumber) {
        WifiFragment fragment = new WifiFragment();
        Bundle args = new Bundle();
        args.putInt("section_number", sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        model = new ViewModelProvider(this).get(WifiViewModel.class);
        setHasOptionsMenu(true);
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        return inflater.inflate(R.layout.wifi, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        pager = view.findViewById(R.id.wifi_pager);
        pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull @Override public Fragment createFragment(int position) {
                if (position == 1) return new CapturesTab();
                if (position == 2) return new UploadsTab();
                if (position == 3) return new WifiLogFragment();
                return new NetworksTab();
            }
            @Override public int getItemCount() { return 4; }
        });
        int[] titles = {R.string.wifi_networks, R.string.wifi_captures, R.string.wifi_uploads, R.string.wifi_logs};
        mediator = new TabLayoutMediator(view.findViewById(R.id.wifi_tabs), pager,
                (tab, position) -> tab.setText(titles[position]));
        mediator.attach();
        view.findViewById(R.id.wifi_open_session).setOnClickListener(v -> {
            WifiSessionState.Snapshot session = WifiSessionState.current();
            Intent intent = WifiSessionActivity.intent(requireContext());
            WifiSessionState.markCompletedSessionViewed(session.generation);
            startActivity(intent);
        });
        WifiSessionState.live().observe(getViewLifecycleOwner(), session -> {
            boolean targetSession = session.request != null && session.request.hasSessionScreen();
            view.findViewById(R.id.wifi_open_session).setVisibility(targetSession
                    && (session.active || !session.completedSessionViewed) ? View.VISIBLE : View.GONE);
            String target = !targetSession ? "" : session.request.ssid.isEmpty() ? session.request.bssid : session.request.ssid;
            String status = session.status.isEmpty() ? getString(R.string.wifi_stage_preparing) : session.status;
            ((TextView) view.findViewById(R.id.wifi_active_session_status)).setText(getString(R.string.wifi_session_summary, target, status));
        });
        model.changes().observe(getViewLifecycleOwner(), ignored -> model.refresh());
        model.messages.observe(getViewLifecycleOwner(), event -> {
            String message = event == null ? null : event.consume();
            if (message != null) show(this, new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.wifi_title).setMessage(message).setPositiveButton(R.string.wifi_close, null).create());
        });
    }

    @Override public void onResume() {
        super.onResume();
        showRequestedTab();
    }

    public void showRequestedTab() {
        Intent intent = requireActivity().getIntent();
        if (pager != null && intent.hasExtra("wifi_tab")) {
            pager.setCurrentItem(Math.max(0, Math.min(3, intent.getIntExtra("wifi_tab", 0))), false);
            intent.removeExtra("wifi_tab");
        }
    }

    @Override public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.wifi, menu);
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.wifi_menu_refresh) { model.refresh(); model.discover(); return true; }
        if (item.getItemId() == R.id.wifi_menu_restore) { model.restoreRadio(); return true; }
        if (item.getItemId() == R.id.wifi_menu_setup) {
            if (busy(requireContext())) return true;
            Fragment terminal = TerminalFragment.newInstanceWithCommand(R.id.terminal_item,
                    "apt-get update && apt-get install -y iw aircrack-ng hcxtools procps util-linux coreutils");
            requireActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, terminal).addToBackStack(null).commit();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override public void onDestroyView() {
        if (mediator != null) mediator.detach();
        if (pager != null) pager.setAdapter(null);
        mediator = null;
        pager = null;
        super.onDestroyView();
    }

    public static class NetworksTab extends Fragment {
        private WifiViewModel model;
        private Rows adapter;
        private Spinner interfaces;
        private List<WirelessInterface> radios = Collections.emptyList();
        private List<WifiNetwork> lastNetworks;

        @Override public void onCreate(@Nullable Bundle state) {
            super.onCreate(state);
            model = new ViewModelProvider(requireParentFragment()).get(WifiViewModel.class);
        }

        @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
            return inflater.inflate(R.layout.wifi_networks, container, false);
        }

        @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
            View controls = header(this, (RecyclerView) view, R.layout.wifi_networks_header);
            interfaces = controls.findViewById(R.id.wifi_adapter);
            adapter = list((RecyclerView) view, controls);
            model.interfaces.observe(getViewLifecycleOwner(), found -> {
                radios = found;
                List<String> labels = new ArrayList<>();
                for (WirelessInterface radio : found) labels.add(radio.toString());
                if (labels.isEmpty()) labels.add(getString(R.string.wifi_no_adapters));
                interfaces.setAdapter(spinner(requireContext(), labels));
                for (int i = 0; i < found.size(); i++) {
                    if (found.get(i).name.equals(model.selectedInterface())) interfaces.setSelection(i);
                }
                updateControls(controls);
            });
            interfaces.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                    if (position < radios.size()) model.selectedInterface(radios.get(position).name);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            controls.findViewById(R.id.wifi_refresh_adapters).setOnClickListener(v -> model.discover());
            controls.findViewById(R.id.wifi_stop).setOnClickListener(v -> WifiSessionService.stop(requireContext()));
            controls.findViewById(R.id.wifi_scan).setOnClickListener(v -> {
                String iface = selected();
                if (iface == null || busy(requireContext())) return;
                model.start(new WifiSessionRequest(WifiSessionRequest.Mode.SCAN, iface, "", "", "", 0,
                        WifiSessionRequest.DEFAULT_SCAN_SECONDS, false));
            });
            WifiSessionState.live().observe(getViewLifecycleOwner(), session -> {
                if (session.networks != lastNetworks) {
                    lastNetworks = session.networks;
                    List<Row> rows = new ArrayList<>();
                    for (WifiNetwork network : session.networks) {
                        rows.add(new Row(network.bssid, network.ssid.isEmpty() ? getString(R.string.wifi_hidden_network) : network.ssid,
                                getString(R.string.wifi_network_details, network.bssid, network.channel, network.signal,
                                        network.security,
                                        getResources().getQuantityString(R.plurals.wifi_client_count, network.clients.size(), network.clients.size()),
                                        getResources().getQuantityString(R.plurals.wifi_beacon_count, (int) Math.min(Integer.MAX_VALUE, network.beacons), network.beacons)),
                                -1, () -> selectNetwork(network)));
                    }
                    adapter.submitList(rows);
                    controls.findViewById(R.id.wifi_network_empty).setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                }
                updateControls(controls);
            });
            model.working.observe(getViewLifecycleOwner(), ignored -> updateControls(controls));
        }

        private void updateControls(View view) {
            boolean working = Boolean.TRUE.equals(model.working.getValue());
            WifiSessionState.Snapshot session = WifiSessionState.current();
            boolean active = session.active;
            view.findViewById(R.id.wifi_scan).setEnabled(!active && !working && !radios.isEmpty());
            view.findViewById(R.id.wifi_stop).setEnabled(active && session.cancellable && !session.stopRequested);
            view.findViewById(R.id.wifi_scan).setAlpha(view.findViewById(R.id.wifi_scan).isEnabled() ? 1f : 0.45f);
            view.findViewById(R.id.wifi_stop).setAlpha(view.findViewById(R.id.wifi_stop).isEnabled() ? 1f : 0.45f);
            ((TextView) view.findViewById(R.id.wifi_session_status)).setText(session.status.isEmpty()
                    ? getString(active ? R.string.wifi_stage_preparing : R.string.wifi_idle)
                    : getString(R.string.wifi_session_status, session.iface, session.elapsed, session.status));
            view.findViewById(R.id.wifi_refresh_adapters).setEnabled(!active && !working);
            interfaces.setEnabled(!active && !working && !radios.isEmpty());
            view.findViewById(R.id.wifi_network_busy).setVisibility(active || working ? View.VISIBLE : View.GONE);
        }

        private String selected() {
            int position = interfaces.getSelectedItemPosition();
            if (position < 0 || position >= radios.size()) {
                NhPaths.showMessage(requireContext(), getString(R.string.wifi_choose_adapter));
                return null;
            }
            return radios.get(position).name;
        }

        private void selectNetwork(WifiNetwork network) {
            String iface = selected();
            if (iface == null || busy(requireContext())) return;
            if (requireParentFragment().getChildFragmentManager().findFragmentByTag(WifiTargetDialog.TAG) != null) return;
            WifiTargetDialog.newInstance(network, iface)
                    .show(requireParentFragment().getChildFragmentManager(), WifiTargetDialog.TAG);
        }

        @Override public void onDestroyView() {
            if (getView() != null) ((RecyclerView) getView().findViewById(R.id.wifi_network_list)).setAdapter(null);
            interfaces = null; adapter = null; lastNetworks = null;
            super.onDestroyView();
        }
    }

    public static class CapturesTab extends Fragment {
        private WifiViewModel model;
        private ActivityResultLauncher<String[]> importer;

        @Override public void onCreate(@Nullable Bundle state) {
            super.onCreate(state);
            model = new ViewModelProvider(requireParentFragment()).get(WifiViewModel.class);
            importer = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), model::importCaptures);
        }

        @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
            return inflater.inflate(R.layout.wifi_captures, container, false);
        }

        @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
            View controls = header(this, (RecyclerView) view, R.layout.wifi_captures_header);
            Rows rows = list((RecyclerView) view, controls);
            controls.findViewById(R.id.wifi_import).setOnClickListener(v -> importer.launch(new String[]{"*/*"}));
            model.working.observe(getViewLifecycleOwner(), working -> {
                controls.findViewById(R.id.wifi_import).setEnabled(!working);
                controls.findViewById(R.id.wifi_capture_busy).setVisibility(working ? View.VISIBLE : View.GONE);
            });
            model.captures.observe(getViewLifecycleOwner(), captures -> {
                List<Row> items = new ArrayList<>();
                for (WifiStore.Capture capture : captures) {
                    String detail = getString(R.string.wifi_capture_metadata, Formatter.formatShortFileSize(requireContext(), capture.size),
                            date(capture.created), captureState(requireContext(), capture.validation));
                    if (!capture.details.isEmpty()) detail += "\n" + capture.details;
                    items.add(new Row(capture.id, capture.name, detail, -1, () -> actions(capture)));
                }
                rows.submitList(items);
                controls.findViewById(R.id.wifi_capture_empty).setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }

        private void actions(WifiStore.Capture capture) {
            if (requireParentFragment().getChildFragmentManager().findFragmentByTag(WifiCaptureDialog.TAG) != null) return;
            WifiCaptureDialog.newInstance(capture).show(requireParentFragment().getChildFragmentManager(), WifiCaptureDialog.TAG);
        }

        @Override public void onDestroyView() {
            if (getView() != null) ((RecyclerView) getView().findViewById(R.id.wifi_capture_list)).setAdapter(null);
            super.onDestroyView();
        }
    }

    public static class UploadsTab extends Fragment {
        private WifiViewModel model;

        @Override public void onCreate(@Nullable Bundle state) {
            super.onCreate(state);
            model = new ViewModelProvider(requireParentFragment()).get(WifiViewModel.class);
        }

        @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
            return inflater.inflate(R.layout.wifi_uploads, container, false);
        }

        @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
            View controls = header(this, (RecyclerView) view, R.layout.wifi_uploads_header);
            EditText email = controls.findViewById(R.id.wifi_account_email);
            email.setText(model.email());
            controls.findViewById(R.id.wifi_save_email).setOnClickListener(v -> {
                String value = email.getText().toString().trim();
                if (!Patterns.EMAIL_ADDRESS.matcher(value).matches()) { email.setError(getString(R.string.wifi_email_invalid)); return; }
                model.email(value);
                NhPaths.showMessage(requireContext(), getString(R.string.wifi_email_saved));
            });
            controls.findViewById(R.id.wifi_dashboard).setOnClickListener(v -> dashboard(this));
            Rows rows = list((RecyclerView) view, controls);
            model.uploads.observe(getViewLifecycleOwner(), uploads -> {
                List<Row> items = new ArrayList<>();
                List<WifiStore.Capture> captures = model.captures.getValue();
                for (WifiStore.Upload upload : uploads) {
                    String name = upload.captureId;
                    if (captures != null) for (WifiStore.Capture capture : captures) if (capture.id.equals(upload.captureId)) name = capture.name;
                    String detail = getString(R.string.wifi_upload_metadata, upload.email, date(upload.created), uploadState(requireContext(), upload));
                    if (!upload.pending()) detail += "\n" + getString(R.string.wifi_upload_counts, upload.accepted, upload.skipped, upload.rejected);
                    if (!upload.message.isEmpty()) detail += "\n" + upload.message;
                    items.add(new Row(upload.id, name, detail, upload.state.equals("UPLOADING") ? upload.progress : -1, () -> actions(upload)));
                }
                rows.submitList(items);
                controls.findViewById(R.id.wifi_upload_empty).setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }

        private void actions(WifiStore.Upload upload) {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            if (upload.pending()) { labels.add(getString(R.string.wifi_cancel_upload)); actions.add(() -> model.cancel(upload)); }
            if (upload.state.equals("FAILED") || upload.state.equals("UNKNOWN") || upload.state.equals("CANCELLED")
                    || upload.state.equals("REJECTED")) {
                labels.add(getString(R.string.wifi_retry)); actions.add(() -> model.submit(upload.captureId, upload.email));
            }
            labels.add(getString(R.string.wifi_dashboard)); actions.add(() -> dashboard(this));
            show(this, new MaterialAlertDialogBuilder(requireContext()).setTitle(uploadState(requireContext(), upload))
                    .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                    .setNegativeButton(R.string.wifi_close, null).create());
        }

        @Override public void onDestroyView() {
            if (getView() != null) ((RecyclerView) getView().findViewById(R.id.wifi_upload_list)).setAdapter(null);
            super.onDestroyView();
        }
    }

    private static void dashboard(Fragment fragment) {
        try { fragment.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(OnlineHashCrackClient.DASHBOARD))); }
        catch (RuntimeException e) { NhPaths.showMessage(fragment.requireContext(), e.getMessage()); }
    }

    private static boolean busy(Context context) {
        if (!WifiSessionState.active()) return false;
        NhPaths.showMessage(context, context.getString(R.string.wifi_busy));
        return true;
    }

    private static ArrayAdapter<String> spinner(Context context, List<String> entries) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    static void prompt(Fragment owner, String title, String message, String hint, String initial,
                               boolean email, Consumer<String> action) {
        Context context = owner.requireContext();
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        form.setPadding(pad, 0, pad, 0);
        if (!message.isEmpty()) {
            TextView description = new TextView(context);
            description.setText(message);
            form.addView(description);
        }
        TextInputLayout input = new TextInputLayout(context);
        input.setHint(hint);
        TextInputEditText text = new TextInputEditText(context);
        text.setInputType(email ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS : InputType.TYPE_CLASS_TEXT);
        text.setMaxLines(1);
        text.setText(initial);
        input.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(input);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle(title).setView(scroll)
                .setPositiveButton(email ? R.string.wifi_submit : R.string.wifi_save, null)
                .setNegativeButton(R.string.wifi_cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = text.getText() == null ? "" : text.getText().toString().trim();
            if (value.isEmpty() || (email && !Patterns.EMAIL_ADDRESS.matcher(value).matches())) {
                input.setError(context.getString(email ? R.string.wifi_email_invalid : R.string.wifi_name_required));
                return;
            }
            action.accept(value);
            dialog.dismiss();
        }));
        show(owner, dialog);
    }

    static void show(Fragment owner, AlertDialog dialog) {
        LifecycleOwner lifecycle = owner.getViewLifecycleOwner();
        DefaultLifecycleObserver observer = new DefaultLifecycleObserver() {
            @Override public void onDestroy(@NonNull LifecycleOwner destroyed) { dialog.dismiss(); }
        };
        lifecycle.getLifecycle().addObserver(observer);
        dialog.setOnDismissListener(ignored -> lifecycle.getLifecycle().removeObserver(observer));
        dialog.show();
    }

    public static String captureState(Context context, String state) {
        int resource;
        switch (state) {
            case "HANDSHAKE": resource = R.string.wifi_state_handshake; break;
            case "PMKID": resource = R.string.wifi_state_pmkid; break;
            case "HANDSHAKE_AND_PMKID": resource = R.string.wifi_state_both; break;
            case "EMPTY": resource = R.string.wifi_state_empty; break;
            default: resource = R.string.wifi_state_unverified;
        }
        return context.getString(resource);
    }

    private static String uploadState(Context context, WifiStore.Upload upload) {
        int resource;
        switch (upload.state) {
            case "UPLOADING": return context.getString(R.string.wifi_upload_progress, upload.progress);
            case "ACCEPTED": resource = R.string.wifi_upload_accepted; break;
            case "DUPLICATE": resource = R.string.wifi_upload_duplicate; break;
            case "PARTIAL": resource = R.string.wifi_upload_partial; break;
            case "REJECTED": resource = R.string.wifi_upload_rejected; break;
            case "FAILED": resource = R.string.wifi_upload_failed; break;
            case "UNKNOWN": resource = R.string.wifi_upload_unknown; break;
            case "CANCELLED": resource = R.string.wifi_upload_cancelled; break;
            default: resource = R.string.wifi_upload_queued_state;
        }
        return context.getString(resource);
    }

    static String date(long time) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(time)); }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static View header(Fragment owner, RecyclerView view, int layout) {
        view.setLayoutManager(new LinearLayoutManager(view.getContext()));
        return owner.getLayoutInflater().inflate(layout, view, false);
    }

    private static Rows list(RecyclerView view, View header) {
        Rows rows = new Rows();
        view.setAdapter(new ConcatAdapter(new Header(header), rows));
        return rows;
    }

    /** Controls scroll with results, so large fonts and landscape do not hide the list. */
    private static final class Header extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View view;
        Header(View view) { this.view = view; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new RecyclerView.ViewHolder(view) { };
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) { }
        @Override public int getItemCount() { return 1; }
    }

    private static final class Row {
        final String id, title, detail;
        final int progress;
        final Runnable action;
        Row(String id, String title, String detail, int progress, Runnable action) {
            this.id = id; this.title = title; this.detail = detail; this.progress = progress; this.action = action;
        }
    }

    private static final class Rows extends ListAdapter<Row, RowHolder> {
        Rows() {
            super(new DiffUtil.ItemCallback<Row>() {
                @Override public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) { return a.id.equals(b.id); }
                @Override public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
                    return a.title.equals(b.title) && a.detail.equals(b.detail) && a.progress == b.progress;
                }
            });
        }
        @NonNull @Override public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            RowHolder holder = new RowHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.wifi_list_item, parent, false));
            holder.itemView.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) getItem(position).action.run();
            });
            return holder;
        }
        @Override public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            Row row = getItem(position);
            holder.title.setText(row.title);
            holder.detail.setText(row.detail);
            holder.progress.setVisibility(row.progress < 0 ? View.GONE : View.VISIBLE);
            holder.progress.setProgress(Math.max(0, row.progress));
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title, detail;
        final ProgressBar progress;
        RowHolder(View view) {
            super(view);
            title = view.findViewById(R.id.wifi_row_title);
            detail = view.findViewById(R.id.wifi_row_detail);
            progress = view.findViewById(R.id.wifi_row_progress);
        }
    }
}
