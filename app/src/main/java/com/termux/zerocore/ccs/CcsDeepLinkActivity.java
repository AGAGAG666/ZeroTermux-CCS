package com.termux.zerocore.ccs;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * Android custom-scheme trampoline for cc-switch imports.
 *
 * <p>The WebView host stays non-exported. This activity accepts only
 * {@code ccswitch://v1/import?...}, forwards the URI inside this app, then
 * immediately finishes. The URI is never logged because provider links can carry apiKey.
 */
public final class CcsDeepLinkActivity extends Activity {
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent source = getIntent();
        Uri data = source != null ? source.getData() : null;
        String url = data != null ? data.toString() : null;
        if (source != null) source.setData(null);
        if (!CcsDeepLinkValidator.isSupported(url)) {
            Toast.makeText(this, "不支持的 CC Switch 导入链接", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Intent target = new Intent(this, CcsSwitchActivity.class)
            .putExtra(CcsSwitchActivity.EXTRA_DEEP_LINK, url)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(target);
        finish();
    }
}
