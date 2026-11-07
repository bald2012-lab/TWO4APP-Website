package de.two4app.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.res.Configuration;
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
import android.text.Editable;
import android.text.TextWatcher;
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
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.TextUtils.TruncateAt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.Arrays;
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
    private TextView browserLocation, browserHint;
    private Runnable autoSaveJob;
    private FrameLayout browserFrame;
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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int horizontalSpace = dp(36);
        int contentWidth = Math.max(dp(600), Math.min(dp(1320), screenWidth - horizontalSpace * 2));
        int contentHeight = Math.max(dp(350), screenHeight - dp(38));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setGravity(Gravity.TOP);
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(contentWidth, contentHeight, Gravity.CENTER);
        root.addView(columns, pageParams);

        int leftWidth = Math.max(dp(290), Math.min(dp(430), Math.round(contentWidth * 0.40f)));
        mainPanel = new LinearLayout(this);
        mainPanel.setOrientation(LinearLayout.VERTICAL);
        columns.addView(mainPanel, new LinearLayout.LayoutParams(leftWidth, -1));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        mainPanel.addView(heading, block(0, 9));
        heading.addView(text("TWO4APP  ·  DOWNLOADER", 18, ACCENT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView version = text("v1.1 TV", 13, WHITE, true);
        version.setPadding(dp(9), dp(5), dp(9), dp(5));
        version.setBackground(rounded(0xFF34566D, 9));
        heading.addView(version);

        LinearLayout inputCard = card();
        inputCard.setPadding(dp(13), dp(12), dp(13), dp(12));
        mainPanel.addView(inputCard, block(0, 8));
        inputCard.addView(text("AFTVnews-Code", 16, WHITE, true));

        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setTextColor(WHITE);
        addressInput.setHintTextColor(MUTED);
        addressInput.setHint("z. B. 272483");
        addressInput.setTextSize(23);
        addressInput.setSelectAllOnFocus(false);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressInput.setShowSoftInputOnFocus(false); // Physical remote controls keypad by default.
        addressInput.setPadding(dp(11), dp(7), dp(11), dp(7));
        addressInput.setBackground(rounded(0xFF101E31, 9));
        addressInput.setText(getPreferences().getString("lastInput", ""));
        inputCard.addView(addressInput, block(8, 4));
        addressInput.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable bg = rounded(0xFF101E31, 9);
            if (focused) bg.setStroke(dp(2), ACCENT);
            addressInput.setBackground(bg);
        });
        addressInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                resolveInput(); return true;
            }
            return false;
        });
        addressInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleFavoriteSave(); }
            @Override public void afterTextChanged(Editable e) {}
        });

        String[][] pad = {{"1","2","3"},{"4","5","6"},{"7","8","9"},{"⌫","0","C"}};
        for (String[] digits : pad) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = block(5, 0);
            inputCard.addView(row, rowParams);
            for (String digit : digits) {
                Button key = button(digit, () -> {
                    String value = addressInput.getText().toString();
                    if ("⌫".equals(digit)) {
                        if (!value.isEmpty()) value = value.substring(0, value.length() - 1);
                    } else if ("C".equals(digit)) {
                        value = "";
                    } else if (value.length() < 60) {
                        value += digit;
                    }
                    addressInput.setText(value);
                    addressInput.setSelection(addressInput.length());
                }, false);
                key.setTextSize(20);
                LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, dp(43), 1f);
                kp.setMargins(dp(2), 0, dp(2), 0);
                row.addView(key, kp);
            }
        }
        Button go = button("ÖFFNEN / LADEN  ↗", this::resolveInput, true);
        inputCard.addView(go, block(10, 0));
        go.getLayoutParams().height = dp(50);

        status = text("Bereit", 13, MUTED, false);
        status.setMaxLines(2);
        status.setEllipsize(TruncateAt.END);
        mainPanel.addView(status, block(3, 9));

        LinearLayout favoritesBar = new LinearLayout(this);
        favoritesBar.setGravity(Gravity.CENTER_VERTICAL);
        mainPanel.addView(favoritesBar, block(1, 7));
        favoritesBar.addView(text("MEINE CODES", 16, ACCENT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        Button fileButton = button("DATEIEN", this::showDownloadsDialog, false);
        fileButton.setTextSize(12);
        favoritesBar.addView(fileButton, new LinearLayout.LayoutParams(dp(102), dp(36)));

        ScrollView favoriteScroll = new ScrollView(this);
        favoriteScroll.setFillViewport(false);
        favoriteScroll.setVerticalScrollBarEnabled(true);
        savedList = new LinearLayout(this);
        savedList.setOrientation(LinearLayout.VERTICAL);
        favoriteScroll.addView(savedList);
        mainPanel.addView(favoriteScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        filesList = new LinearLayout(this);
        filesList.setOrientation(LinearLayout.VERTICAL);

        View spacer = new View(this);
        columns.addView(spacer, new LinearLayout.LayoutParams(dp(17), 1));

        browserPanel = card();
        browserPanel.setPadding(dp(12), dp(10), dp(12), dp(12));
        columns.addView(browserPanel, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout browserToolbar = new LinearLayout(this);
        browserToolbar.setGravity(Gravity.CENTER_VERTICAL);
        browserPanel.addView(browserToolbar, block(0, 7));
        browserToolbar.addView(text("WEB-ANSICHT", 16, ACCENT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        browserToolbar.addView(button("◀", () -> {if (webView.canGoBack()) webView.goBack();}, false),
                new LinearLayout.LayoutParams(dp(42), dp(43)));
        browserToolbar.addView(button("▶", () -> {if (webView.canGoForward()) webView.goForward();}, false),
                new LinearLayout.LayoutParams(dp(42), dp(43)));
        browserToolbar.addView(button("↻", () -> {if (webView.getUrl()!=null) webView.reload();}, false),
                new LinearLayout.LayoutParams(dp(42), dp(43)));

        browserLocation = text("Keine Seite geöffnet", 13, MUTED, false);
        browserLocation.setSingleLine(true);
        browserLocation.setEllipsize(TruncateAt.MIDDLE);
        browserPanel.addView(browserLocation, block(0, 8));

        browserFrame = new FrameLayout(this);
        browserFrame.setBackground(rounded(0xFFFCFCFC, 10));
        browserPanel.addView(browserFrame, new LinearLayout.LayoutParams(-1, 0, 1f));
        browserHint = text("Code links eingeben und öffnen.\nDie Webseite erscheint hier.", 21, 0xFF3B5369, false);
        browserHint.setGravity(Gravity.CENTER);
        browserHint.setPadding(dp(15), dp(15), dp(15), dp(15));
        browserFrame.addView(browserHint, new FrameLayout.LayoutParams(-1, -1));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setVisibility(View.GONE);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        browserFrame.addView(webView, new FrameLayout.LayoutParams(-1, -1));
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
        options.setGeolocationEnabled(false);
        options.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setDownloadListener((url, agent, contentDisposition, mimetype, length) -> promptDownload(url));
        browserPanel.addView(text("Werbung & Tracker gefiltert · Downloads mit Bestätigung", 12, MUTED, false), block(8, 0));
        return root;
    }

    void onBrowserNavigation(String url) {
        if (browserLocation != null) browserLocation.setText(url);
    }

    private void showDownloadsDialog() {
        renderFiles();
        ViewGroup parent = (ViewGroup) filesList.getParent();
        if (parent != null) parent.removeView(filesList);
        ScrollView listScroll = new ScrollView(this);
        listScroll.setPadding(dp(12), dp(12), dp(12), dp(12));
        listScroll.addView(filesList);
        new AlertDialog.Builder(this).setTitle("Heruntergeladene Dateien")
                .setView(listScroll).setPositiveButton("Schließen", null).show();
    }

    private SharedPreferences getPreferences() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void resolveInput() {
        String input = addressInput.getText().toString().trim();
        if (input.isEmpty()) { status("Bitte einen Code eingeben."); return; }
        if(autoSaveJob!=null)handler.removeCallbacks(autoSaveJob);
        rememberCodeIfNeeded(input);
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

    private void scheduleFavoriteSave() {
        if (autoSaveJob != null) handler.removeCallbacks(autoSaveJob);
        String code = addressInput.getText().toString().trim();
        if (!code.matches("[0-9]{4,15}")) return;
        autoSaveJob = () -> rememberCodeIfNeeded(code);
        handler.postDelayed(autoSaveJob, 2200);
    }

    private void rememberCodeIfNeeded(String code) {
        if (!code.matches("[0-9]{1,15}") && !code.startsWith("https://")) return;
        try {
            JSONArray favs = favorites();
            for (int i=0; i<favs.length(); i++) {
                JSONObject o=favs.optJSONObject(i);
                if (o != null && code.equals(o.optString("value"))) return;
            }
            if (favs.length() >= 60) return;
            JSONObject item=new JSONObject();
            item.put("value",code);
            item.put("name",code.matches("[0-9]+") ? "Code " + code : code);
            item.put("description", "");
            favs.put(item);
            getPreferences().edit().putString("favorites",favs.toString()).apply();
            renderFavorites();
        } catch (Exception ignored) { status("Code konnte nicht gespeichert werden."); }
    }

    private void editFavorite(String code) {
        JSONArray favs=favorites();
        JSONObject item=null;
        for (int i=0;i<favs.length();i++) {
            JSONObject candidate=favs.optJSONObject(i);
            if (candidate!=null && code.equals(candidate.optString("value"))) {item=candidate;break;}
        }
        if (item==null) return;
        JSONObject selected=item;
        LinearLayout form=new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22),dp(5),dp(22),dp(5));
        EditText name=new EditText(this);
        name.setSingleLine(true);
        name.setText(selected.optString("name"));
        name.setHint("App-Name");
        name.setTextColor(WHITE);
        form.addView(name);
        EditText desc=new EditText(this);
        desc.setSingleLine(false);
        desc.setMinLines(2);
        desc.setMaxLines(4);
        desc.setText(selected.optString("description"));
        desc.setHint("Beschreibung der App");
        desc.setTextColor(WHITE);
        form.addView(desc);
        new AlertDialog.Builder(this).setTitle("Code " + code + " bearbeiten")
                .setView(form).setNegativeButton("Abbrechen",null)
                .setPositiveButton("Speichern",(dialog,which)->{
                    String label=name.getText().toString().trim();
                    String description=desc.getText().toString().trim();
                    try {
                        selected.put("name", label.isEmpty() ? "Code " + code : label.substring(0,Math.min(80,label.length())));
                        selected.put("description",description.substring(0,Math.min(300,description.length())));
                        getPreferences().edit().putString("favorites",favs.toString()).apply();
                        renderFavorites();
                    } catch (Exception e) { status("Bearbeiten fehlgeschlagen."); }
                }).show();
    }

    private JSONArray favorites() {
        try { return new JSONArray(getPreferences().getString("favorites", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void renderFavorites() {
        savedList.removeAllViews();
        JSONArray favs=favorites();
        if(favs.length()==0){
            savedList.addView(text("Noch keine gespeicherten Codes.",14,MUTED,false));return;
        }
        for(int i=favs.length()-1;i>=0;i--){
            JSONObject item=favs.optJSONObject(i);
            if(item==null)continue;
            String code=item.optString("value");
            String name=item.optString("name",code);
            String description=item.optString("description","");
            LinearLayout card=card();
            card.setPadding(dp(12),dp(10),dp(12),dp(10));
            savedList.addView(card,block(0,7));
            TextView title=text(name,16,WHITE,true);
            card.addView(title);
            if(!description.isEmpty()){
                TextView sub=text(description,13,MUTED,false);
                sub.setMaxLines(3);sub.setEllipsize(TruncateAt.END);
                card.addView(sub,block(3,2));
            }
            card.addView(text(code,12,ACCENT,false),block(2,5));
            LinearLayout actions=new LinearLayout(this);
            card.addView(actions);
            Button go=button("ÖFFNEN",()->{addressInput.setText(code);resolveInput();},true);
            actions.addView(go,new LinearLayout.LayoutParams(0,dp(39),1f));
            Button edit=button("✎",()->editFavorite(code),false);
            edit.setContentDescription("Eintrag bearbeiten: " + name);
            actions.addView(edit,new LinearLayout.LayoutParams(dp(48),dp(39)));
            Button remove=button("×",()->removeFavorite(code),false);
            remove.setContentDescription("Eintrag entfernen: " + name);
            actions.addView(remove,new LinearLayout.LayoutParams(dp(48),dp(39)));
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
        if (browserHint != null) browserHint.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        browserLocation.setText(url);
        webView.loadUrl(url);
        status("Seite rechts geöffnet");
    }

    private void closeBrowser() {
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE);
        browserHint.setVisibility(View.VISIBLE);
        browserLocation.setText("Keine Seite geöffnet");
        renderFiles();
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.getVisibility()==View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
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
        b.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable bg = rounded(primary ? ACCENT : 0xFF405B74, 12);
            if (focused) bg.setStroke(dp(3), WHITE);
            b.setBackground(bg);
            b.setScaleX(focused ? 1.025f : 1f);
            b.setScaleY(focused ? 1.025f : 1f);
        });
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
