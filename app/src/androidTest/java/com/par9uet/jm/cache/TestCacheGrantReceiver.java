package com.par9uet.jm.cache;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Grant test storage access from the provider's owner UID. */
public class TestCacheGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        final String authority = "jmcomic.debug.test.cache-documents";
        final String[] packages = {"jmcomic.debug", "jmcomic.debug.test"};
        final String[] roots = {
            "content://" + authority,
            "content://" + authority + "/tree/root",
            "content://" + authority + "/tree/root/document/root",
        };
        for (String packageName : packages) {
            for (String root : roots) context.grantUriPermission(packageName, android.net.Uri.parse(root), flags);
        }
    }
}
