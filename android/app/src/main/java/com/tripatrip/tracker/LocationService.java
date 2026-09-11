package com.tripatrip.tracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocationService extends Service implements LocationListener {
    private static final String CHANNEL_ID = "trip_a_trip_location";
    private static final int NOTIFICATION_ID = 7401;
    private static final String BROKER = "ssl://broker.emqx.io:8883";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private LocationManager locationManager;
    private SharedPreferences prefs;
    private MqttClient mqtt;
    private MqttConnectOptions mqttOptions;
    private String room;
    private String displayName;
    private String deviceId;
    private JSONArray trail = new JSONArray();

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("tat", MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createNotificationChannel();

        deviceId = prefs.getString("deviceId", null);
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("deviceId", deviceId).apply();
        }
        loadTrail();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String n = intent.getStringExtra("name");
            String r = intent.getStringExtra("room");
            if (n != null && !n.trim().isEmpty()) prefs.edit().putString("name", n.trim()).apply();
            if (r != null && !r.trim().isEmpty()) prefs.edit().putString("room", r.trim()).apply();
        }

        displayName = prefs.getString("name", "Traveler");
        room = prefs.getString("room", "755588edf78b6446a2b301f6a4846f4e");
        prefs.edit().putBoolean("running", true).apply();

        startForeground(NOTIFICATION_ID, buildNotification("Starting GPS…"));
        prepareMqtt();
        requestLocationUpdates();
        return START_STICKY;
    }

    private void prepareMqtt() {
        mqttOptions = new MqttConnectOptions();
        mqttOptions.setAutomaticReconnect(true);
        mqttOptions.setCleanSession(true);
        mqttOptions.setConnectionTimeout(10);
        mqttOptions.setKeepAliveInterval(30);

        networkExecutor.execute(() -> {
            try {
                ensureConnected();
                updateNotification("Online · waiting for GPS");
            } catch (Exception e) {
                updateNotification("GPS active · network reconnect pending");
            }
        });
    }

    private synchronized void ensureConnected() throws MqttException {
        if (mqtt == null) {
            String shortId = deviceId.replace("-", "");
            if (shortId.length() > 18) shortId = shortId.substring(0, 18);
            mqtt = new MqttClient(BROKER, "tat_android_" + shortId, new MemoryPersistence());
        }
        if (!mqtt.isConnected()) mqtt.connect(mqttOptions);
    }

    private void requestLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Location permission missing");
            stopSelf();
            return;
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 8000L, 0f, this);
            }
        } catch (Exception ignored) { }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 12000L, 0f, this);
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onLocationChanged(Location location) {
        long now = System.currentTimeMillis();
        prefs.edit().putLong("lastFix", now).apply();
        appendTrailIfNeeded(location, now);

        try {
            JSONObject pos = new JSONObject();
            pos.put("lat", location.getLatitude());
            pos.put("lng", location.getLongitude());
            pos.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            pos.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
            pos.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
            pos.put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
            pos.put("ts", now);

            JSONObject payload = new JSONObject();
            payload.put("v", 1);
            payload.put("id", deviceId);
            payload.put("name", displayName);
            payload.put("ts", now);
            payload.put("pos", pos);
            payload.put("trail", trail);

            String topic = "trip-a-trip/v1/" + room + "/member/" + deviceId;
            String json = payload.toString();
            int accuracy = location.hasAccuracy() ? Math.round(location.getAccuracy()) : -1;

            networkExecutor.execute(() -> publish(topic, json, accuracy));
        } catch (Exception e) {
            updateNotification("GPS fix received · encode error");
        }
    }

    private void publish(String topic, String json, int accuracy) {
        try {
            ensureConnected();
            MqttMessage msg = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            msg.setQos(0);
            msg.setRetained(true);
            mqtt.publish(topic, msg);
            updateNotification("Live · GPS " + (accuracy >= 0 ? "±" + accuracy + " m" : "active"));
        } catch (Exception e) {
            updateNotification("GPS active · offline, retrying on next fix");
        }
    }

    private void appendTrailIfNeeded(Location location, long now) {
        try {
            boolean append = trail.length() == 0;
            if (!append) {
                JSONObject last = trail.getJSONObject(trail.length() - 1);
                float[] result = new float[1];
                Location.distanceBetween(last.getDouble("lat"), last.getDouble("lng"),
                        location.getLatitude(), location.getLongitude(), result);
                append = result[0] >= 8f;
            }
            if (append) {
                JSONObject point = new JSONObject();
                point.put("lat", location.getLatitude());
                point.put("lng", location.getLongitude());
                point.put("ts", now);
                trail.put(point);
                while (trail.length() > 220) trail.remove(0);
                prefs.edit().putString("trail", trail.toString()).apply();
            }
        } catch (Exception ignored) { }
    }

    private void loadTrail() {
        try {
            trail = new JSONArray(prefs.getString("trail", "[]"));
        } catch (Exception e) {
            trail = new JSONArray();
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Trip-a-Trip GPS",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Background location tracking while travelling");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Trip-a-Trip · Background GPS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        prefs.edit().putBoolean("running", false).apply();
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) { }
        networkExecutor.execute(() -> {
            try {
                if (mqtt != null && mqtt.isConnected()) mqtt.disconnect();
                if (mqtt != null) mqtt.close();
            } catch (Exception ignored) { }
        });
        networkExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }
}
