package hr.ifmont.servis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int DRIVE_OPEN_FILE_REQUEST = 3001;
    private static final int DRIVE_CREATE_FILE_REQUEST = 3002;
    private static final String PREFS = "ifmont_native";
    private static final String PREF_DRIVE_FILE = "drive_file_uri";
    private static final String DRIVE_FILE = "IFmont-AUTO-SYNC.json";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidApp");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent;
                try { intent = params.createIntent(); }
                catch (Exception e) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private Uri getDriveFileUri() {
        String raw = prefs().getString(PREF_DRIVE_FILE, "");
        if (raw == null || raw.isEmpty()) return null;
        try { return Uri.parse(raw); } catch (Exception e) { return null; }
    }

    private void persistDriveUri(Uri uri, Intent data) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (data != null) {
            flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (flags == 0) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
        prefs().edit().putString(PREF_DRIVE_FILE, uri.toString()).apply();
        Toast.makeText(this, "Google Drive sinkronizacija povezana.", Toast.LENGTH_LONG).show();
        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript("window.IFmontDriveFolderSelected&&window.IFmontDriveFolderSelected();", null));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == DRIVE_OPEN_FILE_REQUEST || requestCode == DRIVE_CREATE_FILE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                persistDriveUri(data.getData(), data);
            }
            return;
        }

        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i=0;i<count;i++) results[i]=data.getClipData().getItemAt(i).getUri();
                } else if (data.getData()!=null) results = new Uri[]{data.getData()};
            }
            if (filePathCallback != null) { filePathCallback.onReceiveValue(results); filePathCallback=null; }
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    private SavedFile writeToDownloads(String filename, String dataUrl) throws Exception {
        int comma=dataUrl.indexOf(',');
        String header=comma>=0?dataUrl.substring(0,comma):"";
        String payload=comma>=0?dataUrl.substring(comma+1):dataUrl;
        String mime="application/octet-stream";
        int a=header.indexOf(':'), b=header.indexOf(';');
        if(a>=0&&b>a)mime=header.substring(a+1,b);
        byte[] bytes=Base64.decode(payload,Base64.DEFAULT);
        ContentValues values=new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME,filename);
        values.put(MediaStore.Downloads.MIME_TYPE,mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/IFmont");
        values.put(MediaStore.Downloads.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new Exception("Ne mogu napraviti datoteku");
        try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new Exception("Ne mogu otvoriti datoteku");out.write(bytes);}
        values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(uri,values,null,null);
        return new SavedFile(uri,mime);
    }
    private static class SavedFile { final Uri uri; final String mime; SavedFile(Uri u,String m){uri=u;mime=m;} }

    private void writeDriveText(Uri uri, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Exception first = null;
        try {
            android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rwt");
            if (pfd == null) throw new Exception("Drive datoteka se ne može otvoriti za pisanje");
            try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                out.write(bytes);
                out.flush();
            } finally { pfd.close(); }
            return;
        } catch (Exception e) { first = e; }

        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw first != null ? first : new Exception("Drive datoteka se ne može otvoriti");
            out.write(bytes);
            out.flush();
        }
    }

    private String readDriveText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Drive datoteka se ne može otvoriti za čitanje");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void showDrivePicker() {
        // Do not combine setMessage() and setItems() here. On Android AlertDialog,
        // the message view can hide the list, which made v10.7 look like a dead-end popup.
        new AlertDialog.Builder(this)
            .setTitle("Google Drive sinkronizacija")
            .setItems(new String[]{
                "PRVI UREĐAJ — napravi novu IFmont-AUTO-SYNC.json",
                "DRUGI UREĐAJ — odaberi postojeću IFmont-AUTO-SYNC.json",
                "Odustani"
            }, (dialog, which) -> {
                if (which == 0) {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, DRIVE_FILE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, DRIVE_CREATE_FILE_REQUEST);
                } else if (which == 1) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(intent, DRIVE_OPEN_FILE_REQUEST);
                }
            })
            .show();
    }

    public class AndroidBridge {
        @JavascriptInterface public void saveDataUrl(String filename,String dataUrl){runOnUiThread(()->{try{writeToDownloads(filename,dataUrl);Toast.makeText(MainActivity.this,"Spremljeno u Downloads/IFmont: "+filename,Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(MainActivity.this,"Spremanje nije uspjelo: "+e.getMessage(),Toast.LENGTH_LONG).show();}});}
        @JavascriptInterface public void shareDataUrl(String filename,String dataUrl){runOnUiThread(()->{try{SavedFile f=writeToDownloads(filename,dataUrl);Intent share=new Intent(Intent.ACTION_SEND);share.setType(f.mime);share.putExtra(Intent.EXTRA_STREAM,f.uri);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(share,"Spremi ili podijeli IFmont datoteku"));}catch(Exception e){Toast.makeText(MainActivity.this,"Dijeljenje nije uspjelo: "+e.getMessage(),Toast.LENGTH_LONG).show();}});}

        @JavascriptInterface public boolean hasDriveFolder() {
            return getDriveFileUri() != null;
        }

        @JavascriptInterface public void chooseDriveFolder() {
            runOnUiThread(MainActivity.this::showDrivePicker);
        }

        @JavascriptInterface public String saveDriveBackup(String json) {
            Uri uri = getDriveFileUri();
            if (uri == null) return "NO_FOLDER";
            try {
                writeDriveText(uri, json);
                return "OK";
            } catch (Exception e) {
                return "ERROR:" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        @JavascriptInterface public String loadDriveBackup() {
            Uri uri = getDriveFileUri();
            if (uri == null) return "__NO_FOLDER__";
            try {
                return readDriveText(uri);
            } catch (Exception e) {
                return "__ERROR__:" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        @JavascriptInterface public void disconnectDriveFolder() {
            Uri uri = getDriveFileUri();
            if (uri != null) {
                try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) {}
            }
            prefs().edit().remove(PREF_DRIVE_FILE).apply();
        }
    }
}
