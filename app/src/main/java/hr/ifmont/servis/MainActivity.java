package hr.ifmont.servis;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
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
    private static final int DRIVE_TREE_REQUEST = 3001;
    private static final String PREFS = "ifmont_native";
    private static final String PREF_DRIVE_TREE = "drive_tree_uri";
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

    private Uri getDriveTreeUri() {
        String raw = prefs().getString(PREF_DRIVE_TREE, "");
        if (raw == null || raw.isEmpty()) return null;
        try { return Uri.parse(raw); } catch (Exception e) { return null; }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == DRIVE_TREE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
                prefs().edit().putString(PREF_DRIVE_TREE, uri.toString()).apply();
                Toast.makeText(this, "Google Drive mapa povezana.", Toast.LENGTH_LONG).show();
                if (webView != null) {
                    webView.post(() -> webView.evaluateJavascript("window.IFmontDriveFolderSelected&&window.IFmontDriveFolderSelected();", null));
                }
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

    private Uri rootDocumentUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private Uri findChild(Uri treeUri, String displayName) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(children, projection, null, null, null)) {
            if (c == null) return null;
            int idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (c.moveToNext()) {
                String name = c.getString(nameCol);
                if (displayName.equals(name)) {
                    String id = c.getString(idCol);
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                }
            }
        }
        return null;
    }

    private void writeDriveText(Uri treeUri, String text) throws Exception {
        Uri file = findChild(treeUri, DRIVE_FILE);
        if (file == null) {
            file = DocumentsContract.createDocument(getContentResolver(), rootDocumentUri(treeUri), "application/json", DRIVE_FILE);
            if (file == null) throw new Exception("Ne mogu napraviti Drive datoteku");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(file, "rwt");
            if (pfd == null) throw new Exception("Ne mogu otvoriti Drive datoteku");
            try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                out.write(bytes);
                out.flush();
            } finally { pfd.close(); }
        } catch (Exception first) {
            try (OutputStream out = getContentResolver().openOutputStream(file, "wt")) {
                if (out == null) throw first;
                out.write(bytes);
            }
        }
    }

    private String readDriveText(Uri treeUri) throws Exception {
        Uri file = findChild(treeUri, DRIVE_FILE);
        if (file == null) return "";
        try (InputStream in = getContentResolver().openInputStream(file)) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    public class AndroidBridge {
        @JavascriptInterface public void saveDataUrl(String filename,String dataUrl){runOnUiThread(()->{try{writeToDownloads(filename,dataUrl);Toast.makeText(MainActivity.this,"Spremljeno u Downloads/IFmont: "+filename,Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(MainActivity.this,"Spremanje nije uspjelo: "+e.getMessage(),Toast.LENGTH_LONG).show();}});}
        @JavascriptInterface public void shareDataUrl(String filename,String dataUrl){runOnUiThread(()->{try{SavedFile f=writeToDownloads(filename,dataUrl);Intent share=new Intent(Intent.ACTION_SEND);share.setType(f.mime);share.putExtra(Intent.EXTRA_STREAM,f.uri);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(share,"Spremi ili podijeli IFmont datoteku"));}catch(Exception e){Toast.makeText(MainActivity.this,"Dijeljenje nije uspjelo: "+e.getMessage(),Toast.LENGTH_LONG).show();}});}

        @JavascriptInterface public boolean hasDriveFolder() {
            return getDriveTreeUri() != null;
        }

        @JavascriptInterface public void chooseDriveFolder() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(intent, DRIVE_TREE_REQUEST);
            });
        }

        @JavascriptInterface public String saveDriveBackup(String json) {
            Uri tree = getDriveTreeUri();
            if (tree == null) return "NO_FOLDER";
            try {
                writeDriveText(tree, json);
                return "OK";
            } catch (Exception e) {
                return "ERROR:" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        @JavascriptInterface public String loadDriveBackup() {
            Uri tree = getDriveTreeUri();
            if (tree == null) return "__NO_FOLDER__";
            try {
                return readDriveText(tree);
            } catch (Exception e) {
                return "__ERROR__:" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        @JavascriptInterface public void disconnectDriveFolder() {
            prefs().edit().remove(PREF_DRIVE_TREE).apply();
        }
    }
}
