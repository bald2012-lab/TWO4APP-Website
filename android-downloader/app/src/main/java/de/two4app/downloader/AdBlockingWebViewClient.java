package de.two4app.downloader;

import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/** Local privacy filter for advertisements, known tracking hosts and cosmetic ad containers. */
public final class AdBlockingWebViewClient extends WebViewClient {
    private final MainActivity host;
    private static final String[] BLOCKED_HOSTS={
            "doubleclick.net","googlesyndication.com","googleadservices.com",
            "googletagmanager.com","google-analytics.com","analytics.google.com",
            "adnxs.com","taboola.com","outbrain.com","popads.net",
            "propellerads.com","criteo.com","adskeeper.co.uk","adsterra.com",
            "pubmatic.com","rubiconproject.com","smartadserver.com","yieldmo.com",
            "mgid.com","adsrvr.org","scorecardresearch.com","quantserve.com",
            "hotjar.com","newrelic.com","segment.io","segment.com",
            "mixpanel.com","amplitude.com","pixel.facebook.com","connect.facebook.net",
            "facebook.net","branch.io","onesignal.com","clarity.ms",
            "chartbeat.com","zedo.com","smaato.net","amazon-adsystem.com",
            "advertising.com","clickadu.com","exoclick.com","popcash.net",
            "trafficjunky.net","casalemedia.com","openx.net","bidswitch.net",
            "adsafeprotected.com","moatads.com","statcounter.com"
    };
    // These exact URL prefixes commonly carry first-party tracking pixels; do not block general /api endpoints.
    private static final String[] TRACKER_PATHS={"/collect?","/analytics.js","/gtm.js","/pixel?","/track.gif"};
    AdBlockingWebViewClient(MainActivity host){this.host=host;}
    private boolean blocked(Uri u){
        String hostname=u.getHost();if(hostname==null)return false;
        hostname=hostname.toLowerCase(Locale.ROOT);
        for(String domain:BLOCKED_HOSTS) if(hostname.equals(domain)||hostname.endsWith("."+domain))return true;
        String path=u.getEncodedPath();if(path==null)return false;
        String p=path.toLowerCase(Locale.ROOT);
        String q=u.getEncodedQuery();
        for(String pattern:TRACKER_PATHS){
            if(pattern.endsWith("?")) {
                if(p.equals(pattern.substring(0,pattern.length()-1)) && q!=null)return true;
            } else if(p.endsWith(pattern))return true;
        }
        return false;
    }
    @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
        if(!request.isForMainFrame() && blocked(request.getUrl()))
            return new WebResourceResponse("text/plain","utf-8",new ByteArrayInputStream(new byte[0]));
        return null;
    }
    @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
        Uri uri=request.getUrl();
        if(blocked(uri))return true;
        String scheme=uri.getScheme();
        if(scheme==null||(!scheme.equalsIgnoreCase("https")&&!scheme.equalsIgnoreCase("http")))return true;
        if(request.isForMainFrame()&&uri.getPath()!=null&&
                uri.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(apk|xapk|apks|zip)")){
            host.promptDownload(uri.toString());return true;
        }
        return false;
    }
    @Override public void onPageStarted(WebView view,String url,Bitmap favicon){
        host.onBrowserNavigation(url);
    }
    @Override public void onPageFinished(WebView view,String url){
        host.onBrowserNavigation(url);
        // Cosmetic rules are intentionally limited to obvious advertising containers.
        String script="(function(){if(document.getElementById('__two4app_block'))return;"+
            "var s=document.createElement('style');s.id='__two4app_block';"+
            "s.textContent='ins.adsbygoogle,[id^=google_ads],[id^=div-gpt-ad],"+
            "[class~=ad-banner],[class~=advertisement],[class~=ads-container],"+
            "[data-ad-client],[data-ad-slot],.taboola-widget,.OUTBRAIN{display:none!important}';"+
            "(document.head||document.documentElement).appendChild(s);})();";
        view.evaluateJavascript(script,null);
    }
}
