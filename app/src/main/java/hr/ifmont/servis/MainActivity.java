package hr.ifmont.servis;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
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
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 2001;

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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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

    public class AndroidBridge {
        @JavascriptInterface public void saveDataUrl(String filename,String dataUrl){runOnUiThread(()->{try{writeToDownloads(filename,dataUrl);Toast.makeText(MainActivity.this,"Spremljeno u Downloads/IFmont: "+filename,Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(MainActivity.this,"Spremanje nije uspjelo: "+e.getMessage(),Toast.LENGTH_LONG).show();}});}
        @JavascriptInterface public void shareDataUrl(String filename,String dataUrl){runOnUiThread(()->{try{SavedFile f=writeToDownloads(filename,dataUrl);Intent share=new Intent(Intent.ACTION_SEND);share.setType(f.mime);share.putExtra(Intent.EXTRA_STREAM,f.uri);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(share,"Spremi ili podijeli IFmont datoteku"));}catch(Exception e){Toast.makeText(MainActivity.this,"Dijeljenje nije uspjelo: "+e.getMessage(),Toast.LENGTH_LONG).show();}});}
    }
}
