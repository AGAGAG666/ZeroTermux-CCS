package com.termux.app.reference;

import android.app.Activity;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Browses actual files without importing their contents. */
public final class TerminalReferencePicker {
    public interface Callback { void onPicked(File file); }

    private final Activity activity;
    private final boolean imagesOnly;
    private final Callback callback;
    private AlertDialog dialog;
    private boolean active = true;

    public TerminalReferencePicker(Activity activity, boolean imagesOnly, Callback callback) {
        this.activity = activity;
        this.imagesOnly = imagesOnly;
        this.callback = callback;
    }

    public void show() { showRoots(); }

    private void showRoots() {
        if (!active || activity.isFinishing() || activity.isDestroyed()) return;
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File shared = Environment.getExternalStorageDirectory();
        String[] labels = {activity.getString(R.string.terminal_reference_home),
            activity.getString(R.string.terminal_reference_shared),
            activity.getString(R.string.terminal_reference_screenshots)};
        dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.terminal_reference_location)
            .setItems(labels, (d, which) -> {
                File root = which == 0 ? home : which == 1 ? shared : new File(shared, "DCIM/Screenshots");
                if (root.isDirectory() && root.canRead()) browse(root);
                else {
                    Toast.makeText(activity, R.string.terminal_reference_unreadable, Toast.LENGTH_LONG).show();
                    showRoots();
                }
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> active = false)
            .create();
        dialog.show();
    }

    public void cancel() {
        active = false;
        if (dialog != null) dialog.dismiss();
    }

    private boolean isImage(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return mime != null && mime.startsWith("image/");
    }

    private void browse(File dir) {
        if (!active || activity.isFinishing() || activity.isDestroyed()) return;
        File[] entries = dir.listFiles();
        List<File> files = new ArrayList<>();
        if (entries != null) {
            for (File file : entries) {
                if (file.canRead() && (file.isDirectory() || (file.isFile() && (!imagesOnly || isImage(file))))) {
                    files.add(file);
                }
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        List<File> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        File parent = dir.getParentFile();
        if (parent != null && parent.isDirectory() && parent.canRead()) {
            targets.add(parent);
            labels.add(activity.getString(R.string.terminal_reference_parent));
        }
        for (File file : files) {
            targets.add(file);
            labels.add(file.getName() + (file.isDirectory() ? "/" : ""));
        }
        final boolean[] navigating = {false};
        dialog = new AlertDialog.Builder(activity)
            .setTitle(dir.getAbsolutePath())
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                File selected = targets.get(which);
                navigating[0] = true;
                if (selected.isDirectory() && selected.canRead()) {
                    browse(selected);
                } else if (TerminalReferencePath.isReadableFile(selected) && (!imagesOnly || isImage(selected))) {
                    active = false;
                    callback.onPicked(selected);
                } else {
                    Toast.makeText(activity, R.string.terminal_reference_invalid, Toast.LENGTH_LONG).show();
                    browse(dir);
                }
            })
            .setNeutralButton(R.string.terminal_reference_location, (d, which) -> {
                navigating[0] = true;
                showRoots();
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> active = false)
            .create();
        dialog.setOnDismissListener(d -> { if (!navigating[0]) active = false; });
        dialog.show();
    }
}
