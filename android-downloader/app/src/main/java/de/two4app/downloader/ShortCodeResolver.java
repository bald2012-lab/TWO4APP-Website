package de.two4app.downloader;

import android.net.Uri;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the URL behind public AFTVnews codes. Runs exclusively on a worker thread. */
public final class ShortCodeResolver {
    private static final Pattern REDIRECT_ANCHOR = Pattern.compile(
            "(?is)Redirecting\\s+in.{0,140}?to\\s*:\\s*<a\\s+[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern META_REFRESH = Pattern.compile(
            "(?is)<meta[^>]+http-equiv\\s*=\\s*['\"]refresh['\"][^>]*content\\s*=\\s*['\"][^'\"]*?url=([^'\" >]+)");
    private static final int MAX_HTML = 256 * 1024;

    private ShortCodeResolver() {}

    static Result resolve(String input) throws Exception {
        String value = input.trim();
        String address;
        if (value.matches("[0-9]{1,15}")) {
            address = "https://go.aftvnews.com/" + value;
        } else {
            if (!value.matches("(?i)^https?://.*")) {
                value = "https://" + value;
            }
            Uri uri = Uri.parse(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || TextUtils.isEmpty(uri.getHost()))
                throw new IllegalArgumentException("Nur sichere HTTPS-Adressen sind erlaubt.");
            address = value;
        }
        return follow(address);
    }

    private static Result follow(String address) throws Exception {
        String current = address;
        for (int step = 0; step < 8; step++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol()))
                throw new IllegalArgumentException("Unsichere Weiterleitung (HTTP) blockiert: " + current);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
            try {
                int status = c.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IllegalArgumentException("Weiterleitung ohne Ziel");
                    current = new URL(url, location).toString();
                    continue;
                }
                if (status < 200 || status >= 300)
                    throw new IllegalArgumentException("Serverfehler HTTP " + status);
                String mime = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
                String disposition = c.getHeaderField("Content-Disposition");
                boolean isFile = (!mime.contains("text/html") && !mime.contains("application/xhtml") &&
                        (mime.contains("application/") || mime.contains("binary/"))) ||
                        (disposition != null && disposition.toLowerCase(Locale.ROOT).contains("attachment")) ||
                        (current.toLowerCase(Locale.ROOT).matches(".*\\.(apk|zip|apks|xapk)(\\?.*)?"));
                if (isFile) return new Result(current, true);
                if (!mime.contains("text/html") && !mime.isEmpty()) return new Result(current, false);
                byte[] html = readUpTo(c.getInputStream(), MAX_HTML);
                String page = new String(html, StandardCharsets.UTF_8);
                // AFTVnews publishes the destination in its interstitial HTML.
                if (isAftvnews(url.getHost())) {
                    Matcher m = REDIRECT_ANCHOR.matcher(page);
                    if (m.find()) {
                        String next = unescapeHtml(m.group(1));
                        current = new URL(url, next).toString();
                        continue;
                    }
                    m = META_REFRESH.matcher(page);
                    if (m.find()) {
                        current = new URL(url, unescapeHtml(m.group(1))).toString();
                        continue;
                    }
                }
                return new Result(current, false);
            } finally { c.disconnect(); }
        }
        throw new IllegalArgumentException("Zu viele Weiterleitungen");
    }

    private static boolean isAftvnews(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("aftv.news") || h.equals("go.aftvnews.com") || h.endsWith(".aftvnews.com");
    }

    private static String unescapeHtml(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static byte[] readUpTo(InputStream stream, int maxBytes) throws Exception {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while (out.size() < maxBytes && (count = in.read(buffer, 0, Math.min(buffer.length, maxBytes - out.size()))) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    static final class Result {
        final String url;
        final boolean directFile;
        Result(String url, boolean directFile) { this.url = url; this.directFile = directFile; }
    }
}
