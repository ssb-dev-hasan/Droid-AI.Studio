package com.droid.aistudio;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    private final ActivityResultLauncher<Intent> fileChooser =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback == null) return;
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getDataString() != null) {
                    results = new Uri[]{Uri.parse(result.getData().getDataString())};
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            });

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new ZipDownloadBridge(this), "ZipBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String injectJs = "(function(){" +
                        "if(window.__zipHooked) return; window.__zipHooked = true;" +
                        "const origCreate = URL.createObjectURL;" +
                        "URL.createObjectURL = function(obj){" +
                        "  const u = origCreate.call(URL, obj);" +
                        "  window.__lastBlob = obj;" +
                        "  return u;" +
                        "};" +
                        "window.__sendBlobToAndroid = function(blobUrl, fileName){" +
                        "  const targetName = fileName || 'DroidAIStudio_Project.zip';" +
                        "  const saveBlob = (blob) => {" +
                        "    const reader = new FileReader();" +
                        "    reader.onloadend = () => ZipBridge.saveZip(reader.result, targetName);" +
                        "    reader.readAsDataURL(blob);" +
                        "  };" +
                        "  if (blobUrl && blobUrl.startsWith('blob:')) {" +
                        "    fetch(blobUrl).then(r => r.blob()).then(saveBlob).catch(() => {" +
                        "      if (window.__lastBlob) saveBlob(window.__lastBlob);" +
                        "    });" +
                        "  } else if (window.__lastBlob) {" +
                        "    saveBlob(window.__lastBlob);" +
                        "  }" +
                        "};" +
                        "document.addEventListener('click', function(e){" +
                        "  const link = e.target.closest('a');" +
                        "  if (link && link.href && link.href.startsWith('blob:')) {" +
                        "    e.preventDefault();" +
                        "    window.__sendBlobToAndroid(link.href, link.getAttribute('download'));" +
                        "  }" +
                        "}, true);" +
                        "})();";
                view.evaluateJavascript(injectJs, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                fileChooser.launch(params.createIntent());
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url != null && url.startsWith("blob:")) {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (!name.toLowerCase().endsWith(".zip")) {
                    name = name.replaceAll("\\.[^.]+$", "") + ".zip";
                }
                webView.evaluateJavascript("window.__sendBlobToAndroid('" + url + "', '" + name + "');", null);
                return;
            }
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                req.setMimeType(mimeType);
                req.addRequestHeader("User-Agent", userAgent);
                req.setTitle(fileName);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                    Toast.makeText(this, "ডাউনলোড শুরু হয়েছে: " + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "ডাউনলোড ত্রুটি: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public static class ZipDownloadBridge {
        private final Context context;

        ZipDownloadBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void saveZip(String dataUrl, String fileName) {
            try {
                String base64Part = dataUrl.contains(",") ? dataUrl.split(",")[1] : dataUrl;
                byte[] bytes = Base64.decode(base64Part, Base64.DEFAULT);

                String finalName = (fileName == null || fileName.trim().isEmpty())
                        ? "DroidAIStudio_Project.zip"
                        : fileName.trim();
                if (!finalName.toLowerCase().endsWith(".zip")) {
                    finalName = finalName + ".zip";
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, finalName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        throw new IllegalStateException("MediaStore URI তৈরি করা যায়নি");
                    }
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os == null) {
                            throw new IllegalStateException("OutputStream খোলা যায়নি");
                        }
                        os.write(bytes);
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);
                } else {
                    File downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsFolder.exists()) {
                        downloadsFolder.mkdirs();
                    }
                    File outFile = new File(downloadsFolder, finalName);
                    try (OutputStream os = new FileOutputStream(outFile)) {
                        os.write(bytes);
                    }
                }

                final String savedName = finalName;
                ((AppCompatActivity) context).runOnUiThread(() ->
                        Toast.makeText(context, "ZIP সেভ হয়েছে: Downloads/" + savedName, Toast.LENGTH_LONG).show()
                );
            } catch (Exception e) {
                ((AppCompatActivity) context).runOnUiThread(() ->
                        Toast.makeText(context, "ZIP ডাউনলোড ত্রুটি: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }
    }
}
