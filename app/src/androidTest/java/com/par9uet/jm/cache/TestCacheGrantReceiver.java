package com.par9uet.jm.cache;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.DocumentsContract;

/** Grant test storage access from the provider's owner UID. */
public class TestCacheGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        context.grantUriPermission("jmcomic.debug",
            android.net.Uri.parse("content://jmcomic.debug.test.cache-documents"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }
}
