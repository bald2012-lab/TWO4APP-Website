package de.two4app.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF101A29;
    private static final int CARD = 0xFF202F43;
    private static final int ACCENT = 0xFF70D1FF;
    private static final int WHITE = 0xFFF5FAFF;
    private static final int MUTED = 0xFFADC0D4;
    private static final String PREFS = "two4app_downloader";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText addressInput;
    private TextView status;
    private LinearLayout mainPanel, savedList, filesList;
    private WebView webView;
    private LinearLayout browserPanel;
    private DownloadManager downloads;
    private long activeDownloadId = -1;
    private String activeDownloadName;
    private File pendingInstall;
    private boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(BG);
        downloads = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        setContentView(createLayout());
        renderFavorites();
        renderFiles();
    }

    private View createLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout outer = new LinearLayout(this);
        outer.setPadding(dp(32), dp(24), dp(32), dp(24));
        outer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(outer);

        TextView brand = text("TWO4APP  ·  DOWNLOADER", 25, ACCENT, true);
        outer.addView(brand);
        TextView tagline = text("Kurzcodes eingeben · Apps laden · Ohne Werbung", 16, MUTED, false);
        outer.addView(tagline, block(0, 10));

        mainPanel = new LinearLayout(this);
        mainPanel.setOrientation(LinearLayout.VERTICAL);
        outer.addView(mainPanel, block(16, 0));

        LinearLayout searchCard = card();
        mainPanel.addView(searchCard, block(18, 10));
        searchCard.addView(text("AFTVnews-Code oder HTTPS-Link", 18, WHITE, true));
        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setTextColor(WHITE);
        addressInput.setHintTextColor(MUTED);
        addressInput.setHint("z. B. 272483");
        addressInput.setTextSize(24);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        addressInput.setBackground(rounded(0xFF101E31, 12));
        addressInput.setText(getPreferences().getString("lastInput", ""));
        searchCard.addView(addressInput, block(12, 8));
        addressInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                resolveInput(); return true;
            }
            return false;
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        searchCard.addView(actions, block(10, 6));
        actions.addView(button("ÖFFNEN / LADEN", this::resolveInput, true), new LinearLayout.LayoutParams(0, dp(58), 1f));
        actions.addView(button("MERKEN", this::saveFavorite, false), new LinearLayout.LayoutParams(dp(150), dp(58)));

        status = text("Bereit", 15, MUTED, false);
        mainPanel.addView(status, block(0, 10));
        mainPanel.addView(text("GEMERKTE CODES", 17, ACCENT, true), block(20, 8));
        savedList = new LinearLayout(this);
        savedList.setOrientation(LinearLayout.VERTICAL);
        mainPanel.addView(savedList);
        mainPanel.addView(text("HERUNTERGELADENE DATEIEN", 17, ACCENT, true), block(20, 8));
        filesList = new LinearLayout(this);
        filesList.setOrientation(LinearLayout.VERTICAL);
        mainPanel.addView(filesList);

        browserPanel = new LinearLayout(this);
        browserPanel.setOrientation(LinearLayout.VERTICAL);
        browserPanel.setVisibility(View.GONE);
        outer.addView(browserPanel, new LinearLayout.LayoutParams(-1, -1));
        browserPanel.addView(button("← ZURÜCK", this::closeBrowser, false), block(0, 12));
        browserPanel.addView(text("Webseite · Werbung wird soweit möglich blockiert", 14, MUTED, false));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setWebViewClient(new AdBlockingWebViewClient(this));
        webView.setWebChromeClient(new WebChromeClient());
        WebSettings options = webView.getSettings();
        options.setJavaScriptEnabled(true);
        options.setDomStorageEnabled(true);
        options.setAllowFileAccess(false);
        options.setAllowContentAccess(false);
        options.setJavaScriptCanOpenWindowsAutomatically(false);
        options.setSupportMultipleWindows(false);
        options.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        options.setSafeBrowsingEnabled(true);
        options.setBuiltInZoomControls(true);
        options.setDisplayZoomControls(false);
        webView.setDownloadListener((url, agent, contentDisposition, mimetype, length) -> promptDownload(url));
        browserPanel.addView(webView, new LinearLayout.LayoutParams(-1, dp(460)));
        return scroll;
    }

    private SharedPreferences getPreferences() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void resolveInput() {
        String input = addressInput.getText().toString().trim();
        if (input.isEmpty()) { status("Bitte einen Code eingeben."); return; }
        getPreferences().edit().putString("lastInput", input).apply();
        status("Adresse wird geprüft …");
        io.execute(() -> {
            try {
                ShortCodeResolver.Result r = ShortCodeResolver.resolve(input);
                handler.post(() -> {
                    if (destroyed) return;
                    status("Ziel: " + r.url);
                    if (r.directFile) promptDownload(r.url);
                    else showBrowser(r.url);
                });
            } catch (Exception e) {
                handler.post(() -> { if (!destroyed) status("Fehler: " + e.getMessage()); });
            }
        });
    }

    void promptDownload(String url) {
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || TextUtils.isEmpty(uri.getHost())) {
            status("Unsichere oder ungültige Download-Adresse blockiert."); return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Datei herunterladen?")
                .setMessage("Quelle: " + uri.getHost() + "\n\n" + url +
                        "\n\nInstalliere nur Apps aus vertrauenswürdigen Quellen.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Herunterladen", (d, which) -> beginDownload(url))
                .show();
    }

    private String filenameFor(String url) {
        String last = Uri.parse(url).getLastPathSegment();
        if (last == null) last = "";
        last = last.replaceAll("[^A-Za-z0-9._-]", "_");
        if (last.length() > 80) last = last.substring(0, 80);
        if (!last.matches("(?i).+\\.(apk|zip|xapk|apks)")) last = "app.apk";
        String base = last.replaceAll("(?i)\\.(apk|zip|xapk|apks)$", "");
        String ext = last.substring(base.length());
        return base + "-" + System.currentTimeMillis() + ext.toLowerCase(Locale.ROOT);
    }

    private void beginDownload(String url) {
        try {
            String name = filenameFor(url);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription("TWO4APP Downloader");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setMimeType(name.endsWith(".apk") ? "application/vnd.android.package-archive" : "application/octet-stream");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            request.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36");
            activeDownloadId = downloads.enqueue(request);
            activeDownloadName = name;
            status("Download gestartet: " + name);
            handler.postDelayed(this::pollDownload, 750);
        } catch (Exception e) { status("Download nicht möglich: " + e.getMessage()); }
    }

    private void pollDownload() {
        if (destroyed || activeDownloadId < 0) return;
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(activeDownloadId);
        try (android.database.Cursor c = downloads.query(query)) {
            if (c == null || !c.moveToFirst()) { activeDownloadId = -1; return; }
            int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (state == DownloadManager.STATUS_SUCCESSFUL) {
                status("Fertig: " + activeDownloadName);
                String name = activeDownloadName;
                activeDownloadId = -1;
                renderFiles();
                if (name.endsWith(".apk")) {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir != null) confirmInstall(new File(dir, name));
                }
            } else if (state == DownloadManager.STATUS_FAILED) {
                int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                activeDownloadId = -1;
                status("Download fehlgeschlagen (Code " + reason + ")");
            } else {
                String progress = total > 0 ? (100 * done / total) + "%" : (done / 1024) + " KB";
                status("Lädt: " + progress);
                handler.postDelayed(this::pollDownload, 1000);
            }
        } catch (Exception e) { activeDownloadId = -1; status("Download-Status: " + e.getMessage()); }
    }

    private void confirmInstall(File file) {
        if (!file.isFile()) { status("Datei nicht gefunden."); return; }
        new AlertDialog.Builder(this)
                .setTitle("APK installieren?")
                .setMessage(file.getName() + "\n\nAndroid verlangt eine eigene Bestätigung.")
                .setNegativeButton("Später", null)
                .setPositiveButton("Installieren", (d, which) -> install(file))
                .show();
    }

    private void install(File file) {
        if (!file.getName().endsWith(".apk")) { status("Keine APK-Datei."); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = file;
            new AlertDialog.Builder(this)
                    .setTitle("Installation erlauben")
                    .setMessage("Bitte 'Aus dieser Quelle zulassen' für TWO4APP Downloader aktivieren und zur App zurückkehren.")
                    .setNegativeButton("Abbrechen", (d, which) -> pendingInstall = null)
                    .setPositiveButton("Einstellungen", (d, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(ApkFileProvider.uriFor(file.getName()), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            pendingInstall = null;
        } catch (ActivityNotFoundException e) {
            status("Kein APK-Installationsprogramm verfügbar.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingInstall != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                getPackageManager().canRequestPackageInstalls()) {
            File file = pendingInstall;
            pendingInstall = null;
            handler.post(() -> install(file));
        }
    }

    private void saveFavorite() {
        String code = addressInput.getText().toString().trim();
        if (!code.matches("[0-9]{1,15}") && !code.startsWith("https://")) {
            status("Bitte einen Zahlencode oder eine HTTPS-Adresse eingeben."); return;
        }
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setHint("z. B. Meine TV-App");
        name.setTextColor(WHITE);
        new AlertDialog.Builder(this)
                .setTitle("Code speichern")
                .setView(name)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", (d, which) -> {
                    try {
                        JSONArray favorites = favorites();
                        JSONObject item = new JSONObject();
                        String label = name.getText().toString().trim();
                        item.put("name", label.isEmpty() ? code : label);
                        item.put("value", code);
                        for (int i = favorites.length() - 1; i >= 0; i--) {
                            if (code.equals(favorites.optJSONObject(i).optString("value"))) favorites.remove(i);
                        }
                        favorites.put(item);
                        getPreferences().edit().putString("favorites", favorites.toString()).apply();
                        renderFavorites();
                    } catch (Exception e) { status("Speichern fehlgeschlagen."); }
                }).show();
    }

    private JSONArray favorites() {
        try { return new JSONArray(getPreferences().getString("favorites", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void renderFavorites() {
        savedList.removeAllViews();
        JSONArray favs = favorites();
        if (favs.length() == 0) {
            savedList.addView(text("Noch keine gespeicherten Codes.", 15, MUTED, false)); return;
        }
        for (int i = favs.length() - 1; i >= 0; i--) {
            JSONObject object = favs.optJSONObject(i);
            if (object == null) continue;
            String name = object.optString("name");
            String value = object.optString("value");
            LinearLayout line = card();
            savedList.addView(line, block(0, 8));
            TextView label = text(name + "    ·    " + value, 18, WHITE, false);
            line.addView(label);
            LinearLayout row = new LinearLayout(this);
            line.addView(row);
            row.addView(button("ÖFFNEN", () -> { addressInput.setText(value); resolveInput(); }, true),
                    new LinearLayout.LayoutParams(0, dp(48), 1));
            row.addView(button("ENTFERNEN", () -> removeFavorite(value), false),
                    new LinearLayout.LayoutParams(dp(150), dp(48)));
        }
    }

    private void removeFavorite(String value) {
        new AlertDialog.Builder(this).setTitle("Code entfernen?")
                .setMessage(value)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Entfernen", (d, w) -> {
                    JSONArray list = favorites();
                    for (int i = list.length() - 1; i >= 0; i--) {
                        JSONObject item = list.optJSONObject(i);
                        if (item != null && value.equals(item.optString("value"))) list.remove(i);
                    }
                    getPreferences().edit().putString("favorites", list.toString()).apply();
                    renderFavorites();
                }).show();
    }

    private void renderFiles() {
        filesList.removeAllViews();
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null || files.length == 0) {
            filesList.addView(text("Noch keine Downloads.", 15, MUTED, false)); return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) {
            if (!file.isFile()) continue;
            LinearLayout line = card();
            filesList.addView(line, block(0, 8));
            line.addView(text(file.getName(), 16, WHITE, false));
            LinearLayout actions = new LinearLayout(this);
            line.addView(actions);
            if (file.getName().endsWith(".apk")) {
                actions.addView(button("INSTALLIEREN", () -> confirmInstall(file), true),
                        new LinearLayout.LayoutParams(0, dp(48), 1f));
            }
            actions.addView(button("LÖSCHEN", () -> {
                new AlertDialog.Builder(this).setTitle("Datei löschen?")
                        .setMessage(file.getName())
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Löschen", (d, which) -> {
                            if (!file.delete()) status("Löschen fehlgeschlagen.");
                            renderFiles();
                        }).show();
            }, false), new LinearLayout.LayoutParams(dp(140), dp(48)));
        }
    }

    private void showBrowser(String url) {
        mainPanel.setVisibility(View.GONE);
        browserPanel.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
        status("Seite geöffnet");
    }

    private void closeBrowser() {
        webView.stopLoading();
        webView.loadUrl("about:blank");
        browserPanel.setVisibility(View.GONE);
        mainPanel.setVisibility(View.VISIBLE);
        renderFiles();
    }

    @Override public void onBackPressed() {
        if (browserPanel.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack();
            else closeBrowser();
        } else super.onBackPressed();
    }

    private void status(String message) { status.setText(message); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout.LayoutParams block(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top); p.bottomMargin = dp(bottom); return p;
    }
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }
    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(16), dp(18), dp(16));
        layout.setBackground(rounded(CARD, 18));
        return layout;
    }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(s);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private Button button(String title, Runnable action, boolean primary) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? BG : WHITE);
        b.setBackground(rounded(primary ? ACCENT : 0xFF405B74, 12));
        b.setOnClickListener(v -> action.run());
        b.setFocusable(true);
        return b;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        io.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }
}
