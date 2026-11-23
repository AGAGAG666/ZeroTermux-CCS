package com.termux.app.terminal;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.Toast;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.IOException;

final class TerminalFileManager {
    private TerminalFileManager() {}

    static void reveal(Activity activity, String path) {
        try {
            TerminalFileLink link = TerminalFileLink.resolve(path, TermuxConstants.TERMUX_HOME_DIR,
                Environment.getExternalStorageDirectory());
            if (link == null) {
                Toast.makeText(activity, R.string.terminal_file_link_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
            String authority = link.inHome ? activity.getPackageName() + ".documents" : "com.android.externalstorage.documents";
            Uri folder = DocumentsContract.buildDocumentUri(authority, link.documentId);
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR);
            if (link.inHome) {
                // Some document browsers query the directory and its child-list URI separately.
                ClipData clip = ClipData.newRawUri("directory", folder);
                clip.addItem(new ClipData.Item(DocumentsContract.buildChildDocumentsUri(authority, link.documentId)));
                if (!link.target.equals(link.directory))
                    clip.addItem(new ClipData.Item(DocumentsContract.buildDocumentUri(authority, link.targetDocumentId)));
                view.setClipData(clip);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            CharSequence title = activity.getString(R.string.terminal_file_link_chooser, link.target.getPath());
            if (view.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(Intent.createChooser(view, title));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent browse = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
                activity.startActivity(Intent.createChooser(browse, title));
            } else {
                Toast.makeText(activity, R.string.terminal_file_manager_missing, Toast.LENGTH_SHORT).show();
            }
        } catch (IOException | ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.terminal_file_link_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}
