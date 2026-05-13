package com.example.codexmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "codex_monitor";
    private static final String KEY_SESSIONS = "sessions";
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
    private TextView summary;
    private TextView countBadge;
    private String activeFilter = FILTER_ALL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(ink);
        getWindow().setNavigationBarColor(ink);

        loadSessions();
        if (sessions.isEmpty()) {
            seedExamples();
        }

        setContentView(buildContent());
        render();
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

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setPadding(0, 0, 0, dp(94));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
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

        return hero;
    }

    private void render() {
        int runningCount = count(STATUS_RUNNING);
        int blockedCount = count(STATUS_BLOCKED);
        int doneCount = count(STATUS_DONE);

        countBadge.setText(String.format(Locale.US, "%d total", sessions.size()));
        summary.setText("Tap a status pill to triage work. Use filters to focus on what needs attention.");

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
        for (CodexSession session : sessions) {
            if (FILTER_ALL.equals(activeFilter) || activeFilter.equals(session.status)) {
                View card = card(session);
                card.setAlpha(0f);
                card.setTranslationY(dp(10));
                card.animate().alpha(1f).translationY(0f).setDuration(180L + (shown * 35L)).start();
                list.addView(card);
                shown++;
            }
        }

        if (shown == 0) {
            list.addView(emptyState());
        }
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

        top.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = pill(session.status, statusColor(session.status), false);
        top.addView(status);
        card.addView(top);

        TextView detail = text(session.detail, 15, textSecondary, Typeface.NORMAL);
        detail.setLineSpacing(dp(2), 1.0f);
        detail.setPadding(0, dp(14), 0, dp(16));
        card.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        actions.addView(action("Run", running, () -> updateStatus(session, STATUS_RUNNING)), actionParams(true));
        actions.addView(action("Block", blocked, () -> updateStatus(session, STATUS_BLOCKED)), actionParams(true));
        actions.addView(action("Done", done, () -> updateStatus(session, STATUS_DONE)), actionParams(true));
        actions.addView(action("Edit", textSecondary, () -> showSessionDialog(session)), actionParams(true));
        actions.addView(action("Delete", blocked, () -> confirmDelete(session)), actionParams(false));

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
                sessions.add(0, new CodexSession(
                        UUID.randomUUID().toString(),
                        enteredName,
                        enteredDetail,
                        status.getSelectedItem().toString(),
                        now()));
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
                        item.optString("updatedAt", now())));
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
                now()));
        sessions.add(new CodexSession(
                UUID.randomUUID().toString(),
                "Desktop bridge",
                "Decide whether Android should poll an API or receive push events from a companion service.",
                STATUS_BLOCKED,
                now()));
        sessions.add(new CodexSession(
                UUID.randomUUID().toString(),
                "Local persistence",
                "Session cards survive app restarts using SharedPreferences.",
                STATUS_DONE,
                now()));
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
        action.setOnClickListener(v -> onClick.run());
        return action;
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

        CodexSession(String id, String name, String detail, String status, String updatedAt) {
            this.id = id;
            this.name = name;
            this.detail = detail;
            this.status = status;
            this.updatedAt = updatedAt;
        }
    }
}
