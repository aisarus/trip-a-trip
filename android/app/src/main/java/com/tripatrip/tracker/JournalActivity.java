package com.tripatrip.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

public class JournalActivity extends Activity {
    private static final int FILE_CHOOSER = 301;
    private static final String DEFAULT_ROOM = "755588edf78b6446a2b301f6a4846f4e";
    private static final String JOURNAL_URL = "https://raw.githack.com/aisarus/trip-a-trip/main/final.html";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences p = getSharedPreferences("tat", MODE_PRIVATE);
        String room = getIntent().getStringExtra("room");
        String name = getIntent().getStringExtra("name");
        if (room == null || room.trim().isEmpty()) room = p.getString("room", DEFAULT_ROOM);
        if (name == null || name.trim().isEmpty()) name = p.getString("name", "Viewer");
        String canonical = AuthorizedTravelers.canonical(name);
        final boolean canTravel = canonical != null;
        if (canTravel) name = canonical;
        final String displayName = name;

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(canTravel);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean granted = canTravel && (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);
                callback.invoke(origin, granted, false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> uploadMsg, FileChooserParams fileChooserParams) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = uploadMsg;
                cameraUri = null;

                Intent gallery = new Intent(Intent.ACTION_GET_CONTENT);
                gallery.addCategory(Intent.CATEGORY_OPENABLE);
                gallery.setType("image/*");

                Intent chooser = Intent.createChooser(gallery, "Добавить фото в Trip-a-Trip");
                Intent camera = buildCameraIntent();
                if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});

                try {
                    startActivityForResult(chooser, FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    cameraUri = null;
                    Toast.makeText(JournalActivity.this, "Не удалось открыть выбор фото", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        String url = JOURNAL_URL
                + "?room=" + Uri.encode(room)
                + "&name=" + Uri.encode(displayName)
                + "&role=" + (canTravel ? "travel" : "watch")
                + "&native=1";
        webView.loadUrl(url);
    }

    private Intent buildCameraIntent() {
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (camera.resolveActivity(getPackageManager()) == null) return null;
        try {
            File photo = File.createTempFile("trip_a_trip_", ".jpg", getCacheDir());
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return camera;
        } catch (IOException e) {
            cameraUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER || fileCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) result = new Uri[]{data.getData()};
            else if (cameraUri != null) result = new Uri[]{cameraUri};
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        cameraUri = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}