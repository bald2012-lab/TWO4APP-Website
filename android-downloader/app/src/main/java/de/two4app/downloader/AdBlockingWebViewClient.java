package de.two4app.downloader;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/** Small, conservative blocker: filter known ad networks, never block arbitrary hosts. */
public final class AdBlockingWebViewClient extends WebViewClient {
    private final MainActivity host;
    private static final String[] AD_DOMAINS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
            "propellerads.com", "criteo.com", "adskeeper.co.uk",
            "adsterra.com", "pubmatic.com", "rubiconproject.com",
            "smartadserver.com", "yieldmo.com", "mgid.com"
    };

    AdBlockingWebViewClient(MainActivity host) { this.host = host; }

    private boolean blocked(Uri url) {
        String hostname = url.getHost();
        if (hostname == null) return false;
        hostname = hostname.toLowerCase(Locale.ROOT);
        for (String domain : AD_DOMAINS) {
            if (hostname.equals(domain) || hostname.endsWith("." + domain)) return true;
        }
        return false;
    }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (blocked(request.getUrl())) {
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        }
        return null;
    }

    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (blocked(uri)) return true;
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) return true;
        if (request.isForMainFrame() && uri.getPath() != null &&
                uri.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(apk|xapk|apks|zip)")) {
            host.promptDownload(uri.toString());
            return true;
        }
        return false;
    }
}
