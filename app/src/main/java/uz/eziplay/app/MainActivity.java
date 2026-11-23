package uz.eziplay.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView webView;
    private boolean updateChecked = false;
    private volatile boolean downloading = false;
    private static final int RC_GOOGLE = 9001;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        // Tepa/pastki tizim panellari (status bar, notch, nav bar) ostida kontent qolmasin
        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage (Firebase config uchun)
        s.setAllowFileAccess(true);

        webView.addJavascriptInterface(new NativeBridge(), "EziNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!updateChecked) {
                    updateChecked = true;
                    checkForUpdate();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    /* ---------- JS -> Java ko'prigi ---------- */
    public class NativeBridge {
        @JavascriptInterface
        public String getVersion() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void setStatusColor(String hex, boolean lightBackground) {
            runOnUiThread(() -> applyStatusColor(hex, lightBackground));
        }

        @JavascriptInterface
        public void googleSignIn() {
            runOnUiThread(MainActivity.this::startGoogleSignIn);
        }

        @JavascriptInterface
        public void downloadUpdate(String apkUrl) {
            startDownload(apkUrl);
        }
    }

    /* ---------- Status bar (tepadagi panel) rangi ---------- */
    private void applyStatusColor(String hex, boolean lightBackground) {
        try {
            getWindow().setStatusBarColor(Color.parseColor(hex));
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= 23) {
                // Fon och bo'lsa — yozuvlar (ikonkalar) qora bo'ladi
                flags = lightBackground
                        ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Exception ignored) {
        }
    }

    /* ---------- GitHub'dan yangi versiyani tekshirish ---------- */
    private void checkForUpdate() {
        final String repo = BuildConfig.GITHUB_REPO;
        if (repo == null || repo.startsWith("OWNER/")) return; // repo hali sozlanmagan

        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Eziplay-Android");
                if (c.getResponseCode() != 200) return;

                JSONObject rel = new JSONObject(readAll(c.getInputStream()));
                String tag = rel.optString("tag_name", "");
                String apkUrl = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name", "").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (tag.isEmpty() || apkUrl == null) return;

                JSONObject info = new JSONObject();
                info.put("version", tag.replaceFirst("^[vV]", ""));
                info.put("url", apkUrl);
                final String js = "window.eziOnUpdate && window.eziOnUpdate(" + info.toString() + ")";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            } catch (Exception ignored) {
                // Internet yo'q yoki GitHub javob bermadi — jim o'tamiz
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    /* ---------- APK yuklash va o'rnatish ---------- */
    private void startDownload(String apkUrl) {
        if (downloading) return;

        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            // Birinchi marta: "Noma'lum manbalar" ruxsatini so'raymiz
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            runOnUiThread(() -> {
                startActivity(settings);
                fireJs("eziUpdateError", JSONObject.quote("perm"));
            });
            return;
        }

        downloading = true;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setInstanceFollowRedirects(true); // GitHub -> objects.githubusercontent.com
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Eziplay-Android");
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());

                long total = c.getContentLengthLong();
                File dir = new File(getFilesDir(), "update");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir");
                final File apk = new File(dir, "eziplay-update.apk");

                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int shown = pct;
                                runOnUiThread(() -> fireJs("eziUpdateProgress", String.valueOf(shown)));
                            }
                        }
                    }
                }

                runOnUiThread(() -> {
                    fireJs("eziUpdateReady", "");
                    installApk(apk);
                });
            } catch (Exception e) {
                runOnUiThread(() -> fireJs("eziUpdateError", JSONObject.quote("error")));
            } finally {
                if (c != null) c.disconnect();
                downloading = false;
            }
        }).start();
    }

    private void installApk(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            fireJs("eziUpdateError", JSONObject.quote("error"));
        }
    }

    /* ---------- Google Sign-In (native, WebView'da ishlamaydigan OAuth o'rniga) ---------- */
    private void startGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .requestEmail()
                .build();
        GoogleSignInClient client = GoogleSignIn.getClient(this, gso);
        startActivityForResult(client.getSignInIntent(), RC_GOOGLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_GOOGLE) return;
        try {
            GoogleSignInAccount acc = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
            String token = acc.getIdToken();
            if (token == null) {
                fireJs("eziGoogleError", JSONObject.quote("Google token olinmadi"));
                return;
            }
            fireJs("eziGoogleToken", JSONObject.quote(token));
        } catch (ApiException e) {
            if (e.getStatusCode() == CommonStatusCodes.CANCELED) return; // foydalanuvchi bekor qildi
            fireJs("eziGoogleError", JSONObject.quote("Google kirish xatosi: " + e.getStatusCode()));
        } catch (Exception e) {
            fireJs("eziGoogleError", JSONObject.quote("Google kirish xatosi"));
        }
    }

    /* ---------- Java -> JS ---------- */
    // jsArg — tayyor JavaScript ifodasi (son yoki JSON qatori). UI oqimida chaqiring.
    private void fireJs(String fn, String jsArg) {
        webView.evaluateJavascript(
                "window." + fn + " && window." + fn + "(" + jsArg + ")", null);
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
