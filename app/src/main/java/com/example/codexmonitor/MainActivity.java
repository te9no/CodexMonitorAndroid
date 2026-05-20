package com.example.codexmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "codex_monitor";
    private static final String KEY_SESSIONS = "sessions";
    private static final String KEY_API_BASE_URL = "api_base_url";
    private static final String KEY_API_TOKEN = "api_token";
    private static final String NOTIFICATION_CHANNEL = "codex_monitor_alerts";
    private static final String FILTER_ALL = "ALL";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_BLOCKED = "BLOCKED";
    private static final String STATUS_DONE = "DONE";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US);

    private final List<CodexSession> sessions = new ArrayList<>();
    private final int ink = Color.rgb(13, 18, 15);
    private final int moss = Color.rgb(18, 30, 24);
    private final int panel = Color.rgb(25, 38, 31);
    private final int panelHigh = Color.rgb(34, 51, 42);
    private final int line = Color.rgb(58, 78, 65);
    private final int textPrimary = Color.rgb(247, 244, 235);
    private final int textSecondary = Color.rgb(184, 197, 185);
    private final int muted = Color.rgb(117, 137, 122);
    private final int accent = Color.rgb(218, 255, 104);
    private final int running = Color.rgb(105, 214, 150);
    private final int blocked = Color.rgb(255, 128, 105);
    private final int done = Color.rgb(116, 180, 255);

    private LinearLayout list;
    private LinearLayout filterRow;
    private LinearLayout statsRow;
    private ScrollView sessionScroll;
    private TextView summary;
    private TextView countBadge;
    private TextView connectionStatus;
    private TextView backendStatus;
    private String activeFilter = FILTER_ALL;
    private String apiBaseUrl = "";
    private String apiToken = "";
    private float pullStartY = 0f;
    private boolean pullRefreshReady = false;
    private boolean detailDialogShowing = false;
    private boolean promptDialogShowing = false;
    private String loadingDetailSessionId = "";
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Set<String> notifiedApprovals = new HashSet<>();
    private final Set<String> collapsedGroups = new HashSet<>();
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!apiBaseUrl.isEmpty()) {
                fetchRemoteSessions(false);
            }
            refreshHandler.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(ink);
        getWindow().setNavigationBarColor(ink);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        apiBaseUrl = prefs.getString(KEY_API_BASE_URL, "");
        apiToken = prefs.getString(KEY_API_TOKEN, "");
        createNotificationChannel();
        requestNotificationPermission();
        loadSessions();
        if (sessions.isEmpty()) {
            seedExamples();
        }

        setContentView(buildContent());
        render();
        refreshHandler.postDelayed(autoRefresh, 30000);
    }

    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacks(autoRefresh);
        super.onDestroy();
    }

    private View buildContent() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackground(appBackground());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), 0);
        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(hero());

        statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, dp(16), 0, dp(8));
        root.addView(statsRow);

        filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, dp(4), 0, dp(10));
        root.addView(filterRow);

        sessionScroll = new ScrollView(this);
        sessionScroll.setClipToPadding(false);
        sessionScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sessionScroll.setPadding(0, 0, 0, dp(94));
        sessionScroll.setOnTouchListener(this::handlePullToRefresh);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sessionScroll.addView(list);
        root.addView(sessionScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));

        TextView fab = text("+", 34, ink, Typeface.BOLD);
        fab.setGravity(Gravity.CENTER);
        fab.setContentDescription("Add Codex session");
        fab.setBackground(roundRect(accent, dp(22), accent));
        fab.setElevation(dp(10));
        fab.setOnClickListener(v -> showSessionDialog(null));
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(66), dp(66));
        fabParams.gravity = Gravity.BOTTOM | Gravity.END;
        fabParams.setMargins(0, 0, dp(22), dp(22));
        shell.addView(fab, fabParams);

        return shell;
    }

    private boolean handlePullToRefresh(View view, MotionEvent event) {
        if (apiBaseUrl.isEmpty()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pullStartY = event.getY();
                pullRefreshReady = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (sessionScroll.getScrollY() == 0 && event.getY() - pullStartY > dp(72)) {
                    pullRefreshReady = true;
                    connectionStatus.setText("Release to sync");
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pullRefreshReady && sessionScroll.getScrollY() == 0) {
                    pullRefreshReady = false;
                    fetchRemoteSessions(true);
                }
                break;
            default:
                break;
        }
        return false;
    }

    private View hero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setBackground(roundRect(panel, dp(30), Color.rgb(69, 91, 75)));
        hero.setElevation(dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);

        TextView eyebrow = text("CODEX MONITOR", 12, accent, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.16f);
        titleBlock.addView(eyebrow);

        TextView title = text("Sessions", 34, textPrimary, Typeface.BOLD);
        title.setPadding(0, dp(3), 0, 0);
        titleBlock.addView(title);

        top.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        countBadge = text("", 13, ink, Typeface.BOLD);
        countBadge.setGravity(Gravity.CENTER);
        countBadge.setPadding(dp(12), dp(8), dp(12), dp(8));
        countBadge.setBackground(roundRect(accent, dp(999), accent));
        top.addView(countBadge);
        hero.addView(top);

        summary = text("", 15, textSecondary, Typeface.NORMAL);
        summary.setPadding(0, dp(14), 0, 0);
        hero.addView(summary);

        LinearLayout connectionRow = new LinearLayout(this);
        connectionRow.setOrientation(LinearLayout.HORIZONTAL);
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);
        connectionRow.setPadding(0, dp(14), 0, 0);

        connectionStatus = text("", 12, textSecondary, Typeface.BOLD);
        connectionStatus.setPadding(0, 0, dp(10), 0);
        connectionRow.addView(connectionStatus, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        TextView server = smallButton("Server", textSecondary);
        server.setOnClickListener(v -> showServerDialog());
        connectionRow.addView(server, compactButtonParams(true));

        TextView sync = smallButton("Sync", accent);
        sync.setOnClickListener(v -> fetchRemoteSessions(true));
        connectionRow.addView(sync, compactButtonParams(false));

        hero.addView(connectionRow);

        backendStatus = text("Remote backend not checked yet.", 12, muted, Typeface.BOLD);
        backendStatus.setPadding(0, dp(10), 0, 0);
        hero.addView(backendStatus);

        return hero;
    }

    private void render() {
        int runningCount = count(STATUS_RUNNING);
        int blockedCount = count(STATUS_BLOCKED);
        int doneCount = count(STATUS_DONE);

        countBadge.setText(String.format(Locale.US, "%d total", sessions.size()));
        summary.setText("Tap a status pill to triage work. Use filters to focus on what needs attention.");
        if (apiBaseUrl.isEmpty()) {
            connectionStatus.setText("Manual mode. Set Tailscale server URL.");
            backendStatus.setText("Remote backend not configured.");
        } else {
            connectionStatus.setText("Server " + apiBaseUrl);
        }

        renderStats(runningCount, blockedCount, doneCount);
        renderFilters(runningCount, blockedCount, doneCount);
        renderSessions();
    }

    private void renderStats(int runningCount, int blockedCount, int doneCount) {
        statsRow.removeAllViews();
        statsRow.addView(metric("Active", runningCount, running), metricParams(true, true));
        statsRow.addView(metric("Blocked", blockedCount, blocked), metricParams(false, true));
        statsRow.addView(metric("Done", doneCount, done), metricParams(false, false));
    }

    private void renderFilters(int runningCount, int blockedCount, int doneCount) {
        filterRow.removeAllViews();
        filterRow.addView(filterChip("All", FILTER_ALL, sessions.size()));
        filterRow.addView(filterChip("Run", STATUS_RUNNING, runningCount));
        filterRow.addView(filterChip("Blocked", STATUS_BLOCKED, blockedCount));
        filterRow.addView(filterChip("Done", STATUS_DONE, doneCount));
    }

    private void renderSessions() {
        list.removeAllViews();
        int shown = 0;
        int renderedGroups = 0;
        Map<String, List<CodexSession>> groups = groupedVisibleSessions();
        for (Map.Entry<String, List<CodexSession>> entry : groups.entrySet()) {
            boolean collapsed = collapsedGroups.contains(entry.getKey());
            list.addView(groupHeader(entry.getKey(), entry.getValue(), collapsed));
            renderedGroups++;
            if (collapsed) {
                continue;
            }
            int groupShown = 0;
            for (CodexSession session : entry.getValue()) {
                View card = card(session);
                card.setAlpha(0f);
                card.setTranslationY(dp(10));
                card.animate().alpha(1f).translationY(0f).setDuration(180L + (shown * 35L)).start();
                list.addView(card);
                shown++;
                groupShown++;
            }
            if (groupShown == 0) {
                continue;
            }
        }

        if (shown == 0 && renderedGroups == 0) {
            list.addView(emptyState());
        }
    }

    private Map<String, List<CodexSession>> groupedVisibleSessions() {
        Map<String, List<CodexSession>> groups = new LinkedHashMap<>();
        for (CodexSession session : sessions) {
            if (FILTER_ALL.equals(activeFilter) || activeFilter.equals(session.status)) {
                String group = session.groupName == null || session.groupName.isEmpty()
                        ? "Ungrouped"
                        : session.groupName;
                if (!groups.containsKey(group)) {
                    groups.put(group, new ArrayList<>());
                }
                groups.get(group).add(session);
            }
        }
        return groups;
    }

    private View groupHeader(String groupName, List<CodexSession> grouped, boolean collapsed) {
        int active = 0;
        int blockedCount = 0;
        for (CodexSession session : grouped) {
            if (STATUS_RUNNING.equals(session.status)) {
                active++;
            }
            if (STATUS_BLOCKED.equals(session.status)) {
                blockedCount++;
            }
        }
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        header.setBackground(roundRect(Color.rgb(18, 29, 23), dp(18), Color.rgb(48, 66, 54)));

        TextView title = text((collapsed ? "+ " : "- ") + groupName, 15, textPrimary, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String countText = String.format(Locale.US, "%d sessions", grouped.size());
        if (collapsed) {
            countText += " / hidden";
        }
        if (blockedCount > 0) {
            countText += String.format(Locale.US, " / %d blocked", blockedCount);
        } else if (active > 0) {
            countText += String.format(Locale.US, " / %d active", active);
        }
        TextView count = text(countText, 12, textSecondary, Typeface.BOLD);
        header.addView(count);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, dp(8));
        header.setLayoutParams(params);
        header.setOnClickListener(v -> {
            if (collapsedGroups.contains(groupName)) {
                collapsedGroups.remove(groupName);
            } else {
                collapsedGroups.add(groupName);
            }
            renderSessions();
        });
        return header;
    }

    private View card(CodexSession session) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        card.setBackground(roundRect(panel, dp(26), Color.rgb(55, 73, 60)));
        card.setElevation(dp(3));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(cardParams);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);

        TextView name = text(session.name, 21, textPrimary, Typeface.BOLD);
        titleBlock.addView(name);

        TextView updated = text("Updated " + session.updatedAt, 12, muted, Typeface.NORMAL);
        updated.setPadding(0, dp(4), 0, 0);
        titleBlock.addView(updated);

        if (session.daemonKnown) {
            TextView daemon = text(session.daemonConnected ? "daemon connected" : "daemon not connected", 12,
                    session.daemonConnected ? running : muted, Typeface.BOLD);
            daemon.setPadding(0, dp(4), 0, 0);
            titleBlock.addView(daemon);
        }

        if (session.queuedPromptCount > 0) {
            TextView queued = text(session.queuedPromptCount + " queued prompt" + (session.queuedPromptCount == 1 ? "" : "s"), 12,
                    blocked, Typeface.BOLD);
            queued.setPadding(0, dp(4), 0, 0);
            titleBlock.addView(queued);
        }

        top.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = pill(session.status, statusColor(session.status), false);
        top.addView(status);
        card.addView(top);

        if (session.approvalPending) {
            TextView approval = text("APPROVAL WAITING", 12, blocked, Typeface.BOLD);
            approval.setPadding(0, dp(10), 0, 0);
            card.addView(approval);
        }

        TextView detail = text(session.detail, 15, textSecondary, Typeface.NORMAL);
        detail.setLineSpacing(dp(2), 1.0f);
        detail.setPadding(0, dp(14), 0, dp(16));
        card.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        actions.addView(action("Open", accent, () -> showSessionDetail(session)), actionParams(true));
        actions.addView(action("Prompt", done, () -> showPromptDialog(session)), actionParams(true));
        actions.addView(action("Run", running, () -> updateStatus(session, STATUS_RUNNING)), actionParams(true));
        actions.addView(action("Done", done, () -> updateStatus(session, STATUS_DONE)), actionParams(false));

        card.addView(actions);
        return card;
    }

    private View emptyState() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(54), dp(20), dp(54));
        empty.setBackground(roundRect(Color.rgb(20, 31, 25), dp(28), Color.rgb(47, 65, 53)));

        TextView icon = text("{}", 34, accent, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        empty.addView(icon);

        TextView title = text("No sessions here", 22, textPrimary, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(6));
        empty.addView(title);

        TextView body = text("Change the filter or add a new Codex session.", 15, textSecondary, Typeface.NORMAL);
        body.setGravity(Gravity.CENTER);
        empty.addView(body);

        Button add = primaryButton("Add session");
        add.setOnClickListener(v -> showSessionDialog(null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(18), 0, 0);
        empty.addView(add, params);

        return empty;
    }

    private void showSessionDialog(CodexSession existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(10), dp(4), 0);

        TextView helper = text("Keep names short. Use details for the current blocker, branch, or next action.", 14, Color.rgb(77, 88, 78), Typeface.NORMAL);
        helper.setPadding(0, 0, 0, dp(12));
        form.addView(helper);

        EditText name = field("Session name", 1);
        name.setText(existing == null ? "" : existing.name);
        form.addView(name);

        EditText detail = field("What should be monitored?", 4);
        detail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        detail.setText(existing == null ? "" : existing.detail);
        form.addView(detail);

        Spinner status = new Spinner(this);
        String[] statuses = {STATUS_RUNNING, STATUS_BLOCKED, STATUS_DONE};
        status.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, statuses));
        if (existing != null) {
            for (int i = 0; i < statuses.length; i++) {
                if (statuses[i].equals(existing.status)) {
                    status.setSelection(i);
                    break;
                }
            }
        }
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        spinnerParams.setMargins(0, dp(10), 0, 0);
        form.addView(status, spinnerParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add session" : "Edit session")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String enteredName = name.getText().toString().trim();
            String enteredDetail = detail.getText().toString().trim();
            if (enteredName.isEmpty()) {
                name.setError("Required");
                return;
            }
            if (enteredDetail.isEmpty()) {
                enteredDetail = "No detail provided.";
            }

            if (existing == null) {
                if (!apiBaseUrl.isEmpty()) {
                    createRemoteSession(enteredName, enteredDetail);
                    dialog.dismiss();
                    return;
                }
                sessions.add(0, new CodexSession(
                        UUID.randomUUID().toString(),
                        enteredName,
                        enteredDetail,
                        status.getSelectedItem().toString(),
                        now(),
                        false,
                        new ArrayList<>(),
                        "Manual",
                        "",
                        0,
                        false,
                        false));
            } else {
                existing.name = enteredName;
                existing.detail = enteredDetail;
                existing.status = status.getSelectedItem().toString();
                existing.updatedAt = now();
            }
            saveSessions();
            render();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showServerDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(10), dp(4), 0);

        TextView helper = text("Use your PC Tailscale address and bridge token. Leave token empty only for read-only local testing.", 14, Color.rgb(77, 88, 78), Typeface.NORMAL);
        helper.setPadding(0, 0, 0, dp(12));
        form.addView(helper);

        EditText url = field("Server URL", 1);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(apiBaseUrl);
        form.addView(url);

        EditText token = field("Bridge token", 1);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(apiToken);
        form.addView(token);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Codex server")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            apiBaseUrl = normalizeBaseUrl(url.getText().toString().trim());
            apiToken = token.getText().toString().trim();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_API_BASE_URL, apiBaseUrl)
                    .putString(KEY_API_TOKEN, apiToken)
                    .apply();
            render();
            dialog.dismiss();
            if (!apiBaseUrl.isEmpty()) {
                fetchRemoteSessions(true);
            }
        }));
        dialog.show();
    }

    private void fetchRemoteSessions(boolean showToast) {
        if (apiBaseUrl.isEmpty()) {
            Toast.makeText(this, "Set server URL first", Toast.LENGTH_SHORT).show();
            showServerDialog();
            return;
        }

        if (showToast) {
            connectionStatus.setText("Syncing " + apiBaseUrl);
        }
        new Thread(() -> {
            try {
                String sessionsRaw = httpGet(apiBaseUrl + "/sessions");
                String daemonRaw = "";
                try {
                    daemonRaw = httpGet(apiBaseUrl + "/daemon");
                } catch (Exception ignored) {
                    // Session sync should still work even if daemon status is unavailable.
                }
                List<CodexSession> remote = parseRemoteSessions(sessionsRaw);
                String backend = parseBackendStatus(daemonRaw);
                new Handler(Looper.getMainLooper()).post(() -> {
                    sessions.clear();
                    sessions.addAll(remote);
                    saveSessions();
                    activeFilter = FILTER_ALL;
                    render();
                    backendStatus.setText(backend);
                    notifyApprovalWaits(remote);
                    if (showToast) {
                        Toast.makeText(this, "Synced " + remote.size() + " sessions", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    render();
                    backendStatus.setText("Remote backend check failed.");
                    if (showToast) {
                        Toast.makeText(this, "Sync failed for " + apiBaseUrl + ": " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private List<CodexSession> parseRemoteSessions(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        JSONArray array = root.getJSONArray("sessions");
        List<CodexSession> remote = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            remote.add(new CodexSession(
                    item.optString("id", UUID.randomUUID().toString()),
                    item.optString("name", "Untitled Codex session"),
                    item.optString("detail", "No detail provided."),
                    item.optString("status", STATUS_RUNNING),
                    item.optString("updatedAt", now()),
                    item.optBoolean("approvalPending", false),
                    parseMessages(item.optJSONArray("messages")),
                    sessionGroup(item),
                    item.optString("cwd", ""),
                    item.optInt("queuedPromptCount", 0),
                    item.has("daemonConnected"),
                    item.optBoolean("daemonConnected", false)));
        }
        return remote;
    }

    private String parseBackendStatus(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "Remote backend status unavailable.";
        }
        try {
            JSONObject daemon = new JSONObject(raw).getJSONObject("daemon");
            boolean enabled = daemon.optBoolean("enabled", false);
            boolean connected = daemon.optBoolean("connected", false);
            if (!enabled) {
                return "Remote backend: disabled on PC bridge.";
            }
            if (connected) {
                JSONArray workspaces = daemon.optJSONArray("workspaces");
                int total = workspaces == null ? 0 : workspaces.length();
                int connectedCount = 0;
                if (workspaces != null) {
                    for (int i = 0; i < workspaces.length(); i++) {
                        JSONObject workspace = workspaces.optJSONObject(i);
                        if (workspace != null && workspace.optBoolean("connected", false)) {
                            connectedCount++;
                        }
                    }
                }
                return String.format(Locale.US, "Remote backend: connected (%d/%d workspaces).", connectedCount, total);
            }
            return "Remote backend: " + daemon.optString("error", "not connected");
        } catch (Exception ignored) {
            return "Remote backend status unavailable.";
        }
    }

    private String sessionGroup(JSONObject item) {
        String cwd = item.optString("cwd", "");
        if (!cwd.isEmpty()) {
            String normalized = cwd.replace('\\', '/');
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            int slash = normalized.lastIndexOf('/');
            if (slash >= 0 && slash < normalized.length() - 1) {
                return normalized.substring(slash + 1);
            }
            return normalized;
        }
        String name = item.optString("name", "").trim();
        return name.isEmpty() ? "Ungrouped" : name;
    }

    private List<CodexMessage> parseMessages(JSONArray array) {
        List<CodexMessage> messages = new ArrayList<>();
        if (array == null) {
            return messages;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                messages.add(new CodexMessage(
                        item.optString("time", ""),
                        item.optString("role", "event"),
                        item.optString("text", "")));
            }
        }
        return messages;
    }

    private String normalizeBaseUrl(String value) {
        if (value.isEmpty()) {
            return "";
        }
        String normalized = value.startsWith("http://") || value.startsWith("https://")
                ? value
                : "http://" + value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private void showSessionDetail(CodexSession session) {
        if (detailDialogShowing || session.id.equals(loadingDetailSessionId)) {
            return;
        }
        loadingDetailSessionId = session.id;
        fetchSessionDetail(session, true);
    }

    private void fetchSessionDetail(CodexSession session, boolean showDialog) {
        if (apiBaseUrl.isEmpty()) {
            loadingDetailSessionId = "";
            showLocalSessionDetail(session);
            return;
        }

        new Thread(() -> {
            try {
                JSONObject root = new JSONObject(httpGet(apiBaseUrl + "/sessions/" + pathSegment(session.id)));
                CodexSession detailed = parseSingleSession(root.getJSONObject("session"));
                new Handler(Looper.getMainLooper()).post(() -> {
                    loadingDetailSessionId = "";
                    replaceSession(detailed);
                    render();
                    if (showDialog) {
                        showLocalSessionDetail(detailed);
                    }
                });
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    loadingDetailSessionId = "";
                    showLocalSessionDetail(session);
                });
            }
        }).start();
    }

    private CodexSession parseSingleSession(JSONObject item) {
        return new CodexSession(
                item.optString("id", UUID.randomUUID().toString()),
                item.optString("name", "Untitled Codex session"),
                item.optString("detail", "No detail provided."),
                item.optString("status", STATUS_RUNNING),
                item.optString("updatedAt", now()),
                item.optBoolean("approvalPending", false),
                parseMessages(item.optJSONArray("messages")),
                sessionGroup(item),
                item.optString("cwd", ""),
                item.optInt("queuedPromptCount", 0),
                item.has("daemonConnected"),
                item.optBoolean("daemonConnected", false));
    }

    private void replaceSession(CodexSession updated) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id.equals(updated.id)) {
                sessions.set(i, updated);
                saveSessions();
                return;
            }
        }
        sessions.add(0, updated);
        saveSessions();
    }

    private void showLocalSessionDetail(CodexSession session) {
        if (detailDialogShowing) {
            return;
        }
        detailDialogShowing = true;
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(4), dp(8), dp(4), 0);
        scroll.addView(body);

        String metaText = (session.approvalPending ? "Approval waiting\n" : "")
                + "Updated " + session.updatedAt
                + "\nGroup " + session.groupName;
        if (!session.cwd.isEmpty()) {
            metaText += "\nWorkspace " + session.cwd;
        }
        if (session.daemonKnown) {
            metaText += "\nDaemon " + (session.daemonConnected ? "connected" : "not connected");
        }
        if (session.queuedPromptCount > 0) {
            metaText += "\nQueued prompts " + session.queuedPromptCount;
        }
        TextView meta = text(metaText, 14, Color.rgb(77, 88, 78), Typeface.BOLD);
        body.addView(meta);

        if (session.messages.isEmpty()) {
            TextView empty = text(session.detail, 14, Color.rgb(77, 88, 78), Typeface.NORMAL);
            empty.setPadding(0, dp(12), 0, 0);
            body.addView(empty);
        } else {
            for (CodexMessage message : session.messages) {
                TextView item = text(message.role + "\n" + message.text, 13, Color.rgb(18, 22, 18), Typeface.NORMAL);
                item.setPadding(dp(12), dp(10), dp(12), dp(10));
                item.setBackground(roundRect(Color.rgb(244, 245, 237), dp(14), Color.rgb(212, 217, 205)));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, dp(10), 0, 0);
                body.addView(item, params);
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(session.name)
                .setView(scroll)
                .setNegativeButton("Close", null)
                .setPositiveButton("Prompt", (ignoredDialog, which) -> showPromptDialog(session))
                .create();
        dialog.setOnDismissListener(d -> detailDialogShowing = false);
        dialog.show();
    }

    private void showPromptDialog(CodexSession session) {
        if (promptDialogShowing) {
            return;
        }
        promptDialogShowing = true;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(10), dp(4), 0);

        TextView helper = text("This sends a prompt to the PC bridge. It is queued unless daemon live send is enabled and the target session is connected.", 14, Color.rgb(77, 88, 78), Typeface.NORMAL);
        helper.setPadding(0, 0, 0, dp(12));
        form.addView(helper);

        EditText prompt = field("Prompt for this session", 5);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(prompt);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Send prompt")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = prompt.getText().toString().trim();
            if (text.isEmpty()) {
                prompt.setError("Required");
                return;
            }
            queuePrompt(session, text);
            dialog.dismiss();
        }));
        dialog.setOnDismissListener(d -> promptDialogShowing = false);
        dialog.show();
    }

    private void queuePrompt(CodexSession session, String prompt) {
        if (apiBaseUrl.isEmpty()) {
            Toast.makeText(this, "Set server URL first", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                JSONObject response = postPromptWithFallback(session, prompt);
                boolean sent = response.optBoolean("sent", false);
                boolean queued = response.optBoolean("queued", false);
                String title = sent ? "Prompt sent" : queued ? "Prompt queued" : "Prompt accepted";
                String message = promptResultMessage(response);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (queued) {
                        session.queuedPromptCount += 1;
                        render();
                        saveSessions();
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(title)
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                    fetchRemoteSessions(false);
                });
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(this, "Prompt failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String promptResultMessage(JSONObject response) {
        boolean sent = response.optBoolean("sent", false);
        boolean queued = response.optBoolean("queued", false);
        if (sent) {
            return "The prompt was delivered to the connected Codex daemon.";
        }
        if (!queued) {
            return "The PC bridge accepted the request.";
        }

        JSONObject item = response.optJSONObject("item");
        JSONObject daemon = item == null ? response.optJSONObject("daemon") : item.optJSONObject("daemon");
        String reason = "";
        if (daemon != null) {
            reason = daemon.optString("error", daemon.optString("reason", ""));
        }
        if (reason.isEmpty()) {
            reason = "The PC bridge will retry when the daemon session is available.";
        }
        return "The prompt is stored on the PC bridge queue.\n\nReason: " + reason;
    }

    private JSONObject postPromptWithFallback(CodexSession session, String prompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("prompt", prompt);
        body.put("name", session.name);
        body.put("detail", session.detail);
        try {
            return new JSONObject(httpPost(apiBaseUrl + "/sessions/" + pathSegment(session.id) + "/prompt", body.toString()));
        } catch (IllegalStateException error) {
            String message = error.getMessage() == null ? "" : error.getMessage();
            if (!message.contains("HTTP 404")) {
                throw error;
            }
            JSONObject fallback = new JSONObject();
            fallback.put("name", session.name);
            fallback.put("detail", session.detail);
            fallback.put("prompt", prompt);
            return new JSONObject(httpPost(apiBaseUrl + "/sessions", fallback.toString()));
        }
    }

    private void createRemoteSession(String name, String detail) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("name", name);
                body.put("detail", detail);
                body.put("prompt", name + "\n\n" + detail);
                JSONObject response = new JSONObject(httpPost(apiBaseUrl + "/sessions", body.toString()));
                boolean created = response.optBoolean("created", false);
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, created ? "Session created on PC" : "Session queued on PC", Toast.LENGTH_SHORT).show();
                    fetchRemoteSessions(false);
                });
            } catch (Exception error) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(this, "Create failed: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String httpGet(String urlValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        applyAuth(connection);
        return readResponse(connection);
    }

    private String httpPost(String urlValue, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        applyAuth(connection);
        OutputStream stream = connection.getOutputStream();
        stream.write(bytes);
        stream.close();
        return readResponse(connection);
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        reader.close();
        connection.disconnect();
        if (code < 200 || code >= 300) {
            String message = body.toString();
            try {
                JSONObject json = new JSONObject(message);
                message = json.optString("error", message);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("HTTP " + code + ": " + message);
        }
        return body.toString();
    }

    private void applyAuth(HttpURLConnection connection) {
        if (!apiToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiToken);
        }
    }

    private String pathSegment(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private void updateStatus(CodexSession session, String status) {
        session.status = status;
        session.updatedAt = now();
        saveSessions();
        render();
        Toast.makeText(this, session.name + " -> " + status, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(CodexSession session) {
        new AlertDialog.Builder(this)
                .setTitle("Delete session?")
                .setMessage(session.name + " will be removed from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    sessions.remove(session);
                    saveSessions();
                    render();
                })
                .show();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Codex Monitor alerts",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Approval waiting and session attention alerts");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    private void notifyApprovalWaits(List<CodexSession> remote) {
        for (CodexSession session : remote) {
            if (session.approvalPending && !notifiedApprovals.contains(session.id)) {
                notifiedApprovals.add(session.id);
                showApprovalNotification(session);
            }
            if (!session.approvalPending) {
                notifiedApprovals.remove(session.id);
            }
        }
    }

    private void showApprovalNotification(CodexSession session) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                session.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new android.app.Notification.Builder(this, NOTIFICATION_CHANNEL)
                : new android.app.Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Codex approval waiting")
                .setContentText(session.name)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(session.detail))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(session.id.hashCode(), builder.build());
    }

    private void loadSessions() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String raw = prefs.getString(KEY_SESSIONS, "[]");
        sessions.clear();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                sessions.add(new CodexSession(
                        item.optString("id", UUID.randomUUID().toString()),
                        item.optString("name", "Untitled"),
                        item.optString("detail", "No detail provided."),
                        item.optString("status", STATUS_RUNNING),
                        item.optString("updatedAt", now()),
                        item.optBoolean("approvalPending", false),
                        parseMessages(item.optJSONArray("messages")),
                        item.optString("groupName", item.optString("name", "Ungrouped")),
                        item.optString("cwd", ""),
                        item.optInt("queuedPromptCount", 0),
                        item.has("daemonConnected"),
                        item.optBoolean("daemonConnected", false)));
            }
        } catch (JSONException ignored) {
            sessions.clear();
        }
    }

    private void saveSessions() {
        JSONArray array = new JSONArray();
        for (CodexSession session : sessions) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", session.id);
                item.put("name", session.name);
                item.put("detail", session.detail);
                item.put("status", session.status);
                item.put("updatedAt", session.updatedAt);
                item.put("approvalPending", session.approvalPending);
                item.put("groupName", session.groupName);
                item.put("cwd", session.cwd);
                item.put("queuedPromptCount", session.queuedPromptCount);
                item.put("daemonConnected", session.daemonConnected);
                JSONArray messages = new JSONArray();
                for (CodexMessage message : session.messages) {
                    JSONObject messageJson = new JSONObject();
                    messageJson.put("time", message.time);
                    messageJson.put("role", message.role);
                    messageJson.put("text", message.text);
                    messages.put(messageJson);
                }
                item.put("messages", messages);
                array.put(item);
            } catch (JSONException ignored) {
                // JSONObject only receives primitive strings here.
            }
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_SESSIONS, array.toString())
                .apply();
    }

    private void seedExamples() {
        sessions.add(new CodexSession(
                UUID.randomUUID().toString(),
                "Android UI refresh",
                "Build a polished local dashboard before wiring real Codex telemetry.",
                STATUS_RUNNING,
                now(),
                false,
                new ArrayList<>(),
                "Examples",
                "",
                0,
                false,
                false));
        sessions.add(new CodexSession(
                UUID.randomUUID().toString(),
                "Desktop bridge",
                "Decide whether Android should poll an API or receive push events from a companion service.",
                STATUS_BLOCKED,
                now(),
                false,
                new ArrayList<>(),
                "Examples",
                "",
                0,
                false,
                false));
        sessions.add(new CodexSession(
                UUID.randomUUID().toString(),
                "Local persistence",
                "Session cards survive app restarts using SharedPreferences.",
                STATUS_DONE,
                now(),
                false,
                new ArrayList<>(),
                "Examples",
                "",
                0,
                false,
                false));
        saveSessions();
    }

    private int count(String status) {
        int result = 0;
        for (CodexSession session : sessions) {
            if (status.equals(session.status)) {
                result++;
            }
        }
        return result;
    }

    private View metric(String label, int value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackground(roundRect(Color.rgb(21, 32, 26), dp(22), Color.rgb(48, 66, 54)));

        TextView number = text(String.valueOf(value), 27, color, Typeface.BOLD);
        box.addView(number);

        TextView caption = text(label, 12, textSecondary, Typeface.BOLD);
        caption.setLetterSpacing(0.08f);
        box.addView(caption);
        return box;
    }

    private LinearLayout.LayoutParams metricParams(boolean first, boolean hasRightGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(first ? 0 : dp(5), 0, hasRightGap ? dp(5) : 0, 0);
        return params;
    }

    private TextView filterChip(String label, String filter, int count) {
        boolean selected = activeFilter.equals(filter);
        TextView chip = text(label + " " + count, 13, selected ? ink : textSecondary, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(11), dp(8), dp(11), dp(8));
        chip.setBackground(roundRect(selected ? accent : panelHigh, dp(999), selected ? accent : line));
        chip.setOnClickListener(v -> {
            activeFilter = filter;
            render();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private TextView pill(String value, int color, boolean filled) {
        TextView pill = text(value, 12, filled ? ink : color, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(11), dp(6), dp(11), dp(6));
        pill.setBackground(roundRect(filled ? color : Color.rgb(16, 24, 19), dp(999), color));
        return pill;
    }

    private TextView action(String label, int color, Runnable onClick) {
        TextView action = text(label, 12, color, Typeface.BOLD);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(8), dp(9), dp(8), dp(9));
        action.setBackground(roundRect(Color.rgb(18, 28, 22), dp(14), Color.rgb(48, 66, 54)));
        action.setOnClickListener(v -> {
            v.setEnabled(false);
            v.postDelayed(() -> v.setEnabled(true), 900);
            onClick.run();
        });
        return action;
    }

    private TextView smallButton(String label, int color) {
        TextView button = text(label, 12, color, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackground(roundRect(Color.rgb(18, 28, 22), dp(14), Color.rgb(48, 66, 54)));
        return button;
    }

    private LinearLayout.LayoutParams compactButtonParams(boolean hasRightGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, hasRightGap ? dp(7) : 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams actionParams(boolean hasRightGap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, hasRightGap ? dp(6) : 0, 0);
        return params;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(ink);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setBackground(roundRect(accent, dp(999), accent));
        return button;
    }

    private EditText field(String hint, int minLines) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(Color.rgb(18, 22, 18));
        field.setHintTextColor(Color.rgb(111, 122, 111));
        field.setTextSize(15);
        field.setMinLines(minLines);
        field.setSingleLine(minLines == 1);
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        field.setBackground(roundRect(Color.rgb(244, 245, 237), dp(16), Color.rgb(212, 217, 205)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(10));
        field.setLayoutParams(params);
        return field;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.DEFAULT, style);
        return textView;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private GradientDrawable appBackground() {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(14, 22, 17), moss, ink});
    }

    private int statusColor(String status) {
        if (STATUS_BLOCKED.equals(status)) {
            return blocked;
        }
        if (STATUS_DONE.equals(status)) {
            return done;
        }
        return running;
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class CodexSession {
        final String id;
        String name;
        String detail;
        String status;
        String updatedAt;
        boolean approvalPending;
        List<CodexMessage> messages;
        String groupName;
        String cwd;
        int queuedPromptCount;
        boolean daemonKnown;
        boolean daemonConnected;

        CodexSession(String id, String name, String detail, String status, String updatedAt, boolean approvalPending, List<CodexMessage> messages, String groupName, String cwd, int queuedPromptCount, boolean daemonKnown, boolean daemonConnected) {
            this.id = id;
            this.name = name;
            this.detail = detail;
            this.status = status;
            this.updatedAt = updatedAt;
            this.approvalPending = approvalPending;
            this.messages = messages;
            this.groupName = groupName == null || groupName.isEmpty() ? "Ungrouped" : groupName;
            this.cwd = cwd == null ? "" : cwd;
            this.queuedPromptCount = Math.max(0, queuedPromptCount);
            this.daemonKnown = daemonKnown;
            this.daemonConnected = daemonConnected;
        }
    }

    private static class CodexMessage {
        final String time;
        final String role;
        final String text;

        CodexMessage(String time, String role, String text) {
            this.time = time;
            this.role = role;
            this.text = text;
        }
    }
}
