package uz.otabek.moyden;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_VOICE = 42;
    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onBack ? window.onBack() : false)", value -> {
            if (!"true".equals(value)) defaultBack();
        });
    }

    private void defaultBack() {
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE) return;
        String text = "";
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty() && r.get(0) != null) text = r.get(0);
        }
        js("window.onVoice && window.onVoice(" + JSONObject.quote(text) + ")");
    }

    private void js(final String code) {
        main.post(() -> web.evaluateJavascript(code, null));
    }

    private static String read(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private class Bridge {
        // Native storage: survives WebView data resets
        @JavascriptInterface
        public String load(String key) {
            return getSharedPreferences("moyden", MODE_PRIVATE).getString(key, null);
        }

        @JavascriptInterface
        public void save(String key, String value) {
            getSharedPreferences("moyden", MODE_PRIVATE).edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public void startVoice(final String lang) {
            main.post(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите задачи…");
                try {
                    startActivityForResult(i, REQ_VOICE);
                } catch (ActivityNotFoundException e) {
                    js("window.onVoiceError && window.onVoiceError('no_recognizer')");
                }
            });
        }

        @JavascriptInterface
        public void callClaude(final String id, final String key, final String model, final String prompt) {
            new Thread(() -> {
                JSONObject out = new JSONObject();
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(120000);
                    c.setRequestProperty("content-type", "application/json");
                    c.setRequestProperty("x-api-key", key == null ? "" : key.trim());
                    c.setRequestProperty("anthropic-version", "2023-06-01");

                    JSONObject body = new JSONObject();
                    body.put("model", model);
                    body.put("max_tokens", 4096);
                    JSONArray msgs = new JSONArray();
                    JSONObject m = new JSONObject();
                    m.put("role", "user");
                    m.put("content", prompt);
                    msgs.put(m);
                    body.put("messages", msgs);

                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(bytes);
                    }
                    int code = c.getResponseCode();
                    String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                    out.put("status", code);
                    if (code >= 200 && code < 300) {
                        JSONArray content = new JSONObject(resp).getJSONArray("content");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < content.length(); i++) {
                            JSONObject part = content.getJSONObject(i);
                            if ("text".equals(part.optString("type"))) sb.append(part.optString("text"));
                        }
                        out.put("ok", true);
                        out.put("text", sb.toString());
                    } else {
                        String msg = resp;
                        try {
                            msg = new JSONObject(resp).getJSONObject("error").optString("message", resp);
                        } catch (Exception ignored) {
                        }
                        out.put("ok", false);
                        out.put("error", msg);
                    }
                } catch (Exception e) {
                    try {
                        out.put("ok", false);
                        out.put("status", 0);
                        out.put("error", String.valueOf(e.getMessage()));
                    } catch (Exception ignored) {
                    }
                }
                js("window.onClaude && window.onClaude(" + JSONObject.quote(id) + "," + out.toString() + ")");
            }).start();
        }
    }
}
