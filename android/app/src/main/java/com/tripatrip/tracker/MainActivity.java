package com.tripatrip.tracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 101;
    private static final String DEFAULT_ROOM = "755588edf78b6446a2b301f6a4846f4e";
    private static final String PUBLIC_JOURNAL = "https://raw.githack.com/aisarus/trip-a-trip/main/final.html";

    private EditText nameInput;
    private EditText roomInput;
    private TextView statusText;
    private boolean startAfterPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadSaved();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Trip-a-Trip\nRomania · Complete");
        title.setTextSize(28f);
        title.setTextColor(0xff111827);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Один APK: фоновый GPS + фото + live journal + маршрут + миссии + реакции + открытки + дневные сводки.");
        subtitle.setTextSize(15f);
        subtitle.setTextColor(0xff4b5563);
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle);

        root.addView(label("Имя участника"));
        nameInput = input("Например, Сеня");
        root.addView(nameInput);

        TextView roomLabel = label("Room key");
        roomLabel.setPadding(0, dp(16), 0, dp(6));
        root.addView(roomLabel);
        roomInput = input(DEFAULT_ROOM);
        roomInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(roomInput);

        Button start = button("START GPS + OPEN JOURNAL");
        start.setOnClickListener(v -> startTracker());
        root.addView(start);

        Button journal = button("OPEN FULL JOURNAL");
        journal.setOnClickListener(v -> openJournal());
        root.addView(journal);

        Button share = button("SHARE VIEWER LINK");
        share.setOnClickListener(v -> shareViewerLink());
        root.addView(share);

        Button checkin = button("✓ МЫ ОК · CHECK-IN");
        checkin.setOnClickListener(v -> sendTripEvent(LocationService.ACTION_CHECKIN));
        root.addView(checkin);

        Button sos = button("🚨 SOS EVENT");
        sos.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Отправить SOS в trip-room?")
                .setMessage("Зрители увидят яркий SOS с последней координатой и зарядом. Это НЕ вызов 112.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отправить SOS", (d, w) -> sendTripEvent(LocationService.ACTION_SOS))
                .show());
        root.addView(sos);

        Button stop = button("STOP BACKGROUND GPS");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, LocationService.class));
            getSharedPreferences("tat", MODE_PRIVATE).edit().putBoolean("running", false).apply();
            refreshStatus();
            Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop);

        Button battery = button("BATTERY → UNRESTRICTED");
        battery.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "На Samsung: Battery → Unrestricted", Toast.LENGTH_LONG).show();
        });
        root.addView(battery);

        statusText = new TextView(this);
        statusText.setTextSize(15f);
        statusText.setTextColor(0xff111827);
        statusText.setPadding(0, dp(20), 0, 0);
        root.addView(statusText);

        TextView note = new TextView(this);
        note.setText("После Start экран можно выключить. GPS-публикация разрешена только именам Сеня и Яна. Journal открывается внутри приложения, а зрителям отправляй Share Viewer Link.");
        note.setTextSize(13f);
        note.setTextColor(0xff6b7280);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14f);
        v.setTextColor(0xff374151);
        v.setPadding(0, 0, 0, dp(6));
        return v;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(16f);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(0xfff3f4f6);
        e.setTextColor(0xff111827);
        e.setHintTextColor(0xff9ca3af);
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadSaved() {
        SharedPreferences p = getSharedPreferences("tat", MODE_PRIVATE);
        nameInput.setText(p.getString("name", ""));
        roomInput.setText(p.getString("room", DEFAULT_ROOM));
        refreshStatus();
    }

    private void refreshStatus() {
        SharedPreferences p = getSharedPreferences("tat", MODE_PRIVATE);
        boolean running = p.getBoolean("running", false);
        long last = p.getLong("lastFix", 0L);
        String lastText = last == 0L ? "ещё нет GPS fix" : ((System.currentTimeMillis() - last) / 1000L) + " сек назад";
        if (statusText != null) {
            statusText.setText((running ? "● BACKGROUND GPS ACTIVE" : "○ GPS stopped") + "\nПоследняя координата: " + lastText);
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] identity() {
        String name = nameInput.getText().toString().trim();
        String canonical = AuthorizedTravelers.canonical(name);
        if (canonical != null) name = canonical;
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) room = DEFAULT_ROOM;
        if (name.isEmpty()) name = "Viewer";
        getSharedPreferences("tat", MODE_PRIVATE).edit().putString("name", name).putString("room", room).apply();
        return new String[]{name, room};
    }

    private void startTracker() {
        String rawName = nameInput.getText().toString().trim();
        if (rawName.isEmpty()) {
            nameInput.requestFocus();
            Toast.makeText(this, "Введи имя", Toast.LENGTH_SHORT).show();
            return;
        }
        String canonical = AuthorizedTravelers.canonical(rawName);
        if (canonical == null) {
            nameInput.requestFocus();
            getSharedPreferences("tat", MODE_PRIVATE).edit().putBoolean("running", false).apply();
            Toast.makeText(this, "GPS можно публиковать только как Сеня или Яна", Toast.LENGTH_LONG).show();
            return;
        }
        nameInput.setText(canonical);
        String[] id = identity();
        if (!hasLocationPermission()) {
            startAfterPermission = true;
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(perms.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSIONS);
        }
        actuallyStart(id[0], id[1], true);
    }

    private void actuallyStart(String name, String room, boolean openJournal) {
        String canonical = AuthorizedTravelers.canonical(name);
        if (canonical == null) {
            getSharedPreferences("tat", MODE_PRIVATE).edit().putBoolean("running", false).apply();
            Toast.makeText(this, "GPS заблокирован: разрешены только Сеня и Яна", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, LocationService.class);
        i.putExtra("name", canonical);
        i.putExtra("room", room);
        startForegroundService(i);
        getSharedPreferences("tat", MODE_PRIVATE).edit().putString("name", canonical).putBoolean("running", true).apply();
        refreshStatus();
        Toast.makeText(this, "Background GPS started", Toast.LENGTH_SHORT).show();
        if (openJournal) openJournal();
    }

    private void openJournal() {
        String[] id = identity();
        Intent i = new Intent(this, JournalActivity.class);
        i.putExtra("name", id[0]);
        i.putExtra("room", id[1]);
        startActivity(i);
    }

    private String viewerUrl(String room) {
        return PUBLIC_JOURNAL + "?room=" + Uri.encode(room);
    }

    private void shareViewerLink() {
        String[] id = identity();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Trip-a-Trip · Romania Live");
        send.putExtra(Intent.EXTRA_TEXT, "Смотри наше путешествие вживую:\n" + viewerUrl(id[1]));
        startActivity(Intent.createChooser(send, "Поделиться Trip-a-Trip"));
    }

    private void sendTripEvent(String action) {
        SharedPreferences p = getSharedPreferences("tat", MODE_PRIVATE);
        if (!p.getBoolean("running", false)) {
            Toast.makeText(this, "Сначала запусти Background GPS", Toast.LENGTH_LONG).show();
            return;
        }
        String[] id = identity();
        String canonical = AuthorizedTravelers.canonical(id[0]);
        if (canonical == null) {
            Toast.makeText(this, "События с геопозицией разрешены только Сене и Яне", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, LocationService.class);
        i.setAction(action);
        i.putExtra("name", canonical);
        i.putExtra("room", id[1]);
        startForegroundService(i);
        Toast.makeText(this, LocationService.ACTION_SOS.equals(action) ? "SOS отправляется в trip-room" : "Check-in отправляется", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && startAfterPermission) {
            startAfterPermission = false;
            if (hasLocationPermission()) {
                SharedPreferences p = getSharedPreferences("tat", MODE_PRIVATE);
                String canonical = AuthorizedTravelers.canonical(p.getString("name", ""));
                if (canonical != null) actuallyStart(canonical, p.getString("room", DEFAULT_ROOM), true);
                else Toast.makeText(this, "GPS можно публиковать только как Сеня или Яна", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Без геолокации трекер не сможет работать", Toast.LENGTH_LONG).show();
            }
        }
    }
}