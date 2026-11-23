package com.termux.app.reference;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public final class TerminalReferencePicker {
    public static final int REQUEST = 0x63a1;
    public interface Callback { void onPicked(File file); }

    private final Activity activity;
    private final boolean imagesOnly;
    private final Callback callback;
    private volatile boolean active = true;
    private android.app.AlertDialog confirmation;

    public TerminalReferencePicker(Activity activity, boolean imagesOnly, Callback callback) {
        this.activity = activity;
        this.imagesOnly = imagesOnly;
        this.callback = callback;
    }

    public void show() {
        if (imagesOnly) {
            Intent gallery = new Intent(Intent.ACTION_PICK)
                .setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            try {
                activity.startActivityForResult(gallery, REQUEST);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Some devices provide only GET_CONTENT for photos.
            } catch (SecurityException e) {
                fail();
                return;
            }
        }
        Intent get = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(imagesOnly ? "image/*" : "*/*");
        try {
            activity.startActivityForResult(imagesOnly ? get : Intent.createChooser(get,
                activity.getString(R.string.terminal_reference_manager)), REQUEST);
        } catch (ActivityNotFoundException | SecurityException e) {
            fail();
        }
    }

    public void cancel() {
        active = false;
        if (confirmation != null) confirmation.dismiss();
    }

    public void onResult(int result, Intent data) {
        if (!active) return;
        if (result != Activity.RESULT_OK) { finish(null); return; }
        Uri uri = data == null ? null : data.getData();
        if (uri == null) { fail(); return; }
        new Thread(() -> {
            try {
                File file = resolve(uri).getCanonicalFile();
                boolean privateHome = TerminalReferencePath.isInsideHome(file, TermuxConstants.TERMUX_HOME_DIR);
                activity.runOnUiThread(() -> {
                    if (!active) return;
                    if (activity.isFinishing() || activity.isDestroyed()) { finish(null); return; }
                    if (privateHome) {
                        confirmation = new android.app.AlertDialog.Builder(activity)
                            .setTitle(R.string.terminal_reference_confirm_home)
                            .setMessage(file.getPath())
                            .setPositiveButton(android.R.string.ok, (d, which) -> finish(file))
                            .setNegativeButton(android.R.string.cancel, (d, which) -> finish(null))
                            .setOnCancelListener(d -> finish(null)).show();
                        return;
                    }
                    finish(file);
                    if (cacheRoot().equals(file.getParentFile().getParentFile()))
                        Toast.makeText(activity, R.string.terminal_reference_copy, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException | RuntimeException e) {
                activity.runOnUiThread(this::fail);
            }
        }, "terminal-reference").start();
    }

    private File cacheRoot() { return new File(activity.getCacheDir(), "terminal-references"); }

    private File resolve(Uri uri) throws IOException {
        File shared = Environment.getExternalStorageDirectory().getCanonicalFile();
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            if (uri.getPath() == null || uri.getAuthority() != null && !uri.getAuthority().isEmpty())
                throw new IOException();
            File f = new File(uri.getPath()).getCanonicalFile();
            if (!inside(f, shared)) throw new IOException();
            return check(f);
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || uri.getAuthority() == null)
            throw new IOException();
        String authority = uri.getAuthority();
        if (authority.contains("@")) throw new IOException();
        ProviderInfo info = activity.getPackageManager().resolveContentProvider(authority, 0);
        boolean homeProvider = (activity.getPackageName() + ".documents").equals(authority);
        if (info != null && info.applicationInfo.uid == activity.getApplicationInfo().uid && !homeProvider)
            throw new IOException();
        ContentResolver resolver = activity.getContentResolver();
        try (AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "r")) {
            if (fd == null) throw new IOException();
            File direct = null;
            try {
                direct = new File("/proc/self/fd/" + fd.getParcelFileDescriptor().getFd()).getCanonicalFile();
            } catch (IOException ignored) { /* A pipe has no filesystem path. */ }
            if (direct != null && !homeProvider && TerminalReferencePath.isInsideHome(direct, TermuxConstants.TERMUX_HOME_DIR))
                throw new IOException();
            if (direct != null && (inside(direct, shared) || homeProvider && inside(direct, TermuxConstants.TERMUX_HOME_DIR.getCanonicalFile()))
                && fd.getStartOffset() == 0 && TerminalReferencePath.isReadableFile(direct)
                && (fd.getDeclaredLength() < 0 || fd.getDeclaredLength() == direct.length()))
                return check(direct);
            if (homeProvider) throw new IOException();
            String name = null;
            try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (RuntimeException ignored) { }
            String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri));
            try (InputStream in = fd.createInputStream()) {
                File copy = ReferenceCopy.save(cacheRoot(), name, ext, in, () -> active);
                try { return check(copy); }
                catch (IOException e) { ReferenceCopy.discard(copy); throw e; }
            }
        }
    }

    private static boolean inside(File f, File root) {
        return f.getPath().startsWith(root.getPath() + File.separator);
    }

    private File check(File f) throws IOException {
        if (!active || !TerminalReferencePath.isReadableFile(f)) throw new IOException();
        if (imagesOnly) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException();
        }
        return f;
    }

    private void fail() {
        if (!active) return;
        Toast.makeText(activity, R.string.terminal_reference_invalid, Toast.LENGTH_SHORT).show();
        finish(null);
    }

    private void finish(File file) {
        if (!active) return;
        confirmation = null;
        active = false;
        callback.onPicked(file);
    }
}
