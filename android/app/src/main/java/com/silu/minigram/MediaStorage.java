package com.silu.minigram;

import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Media storage on Firebase Storage (replaces the old shared-secret PHP upload endpoint).
 * Files go to media/{uid}/{category}/{random}; storage.rules only lets the owner write there,
 * so no server secret is needed in the app. MUST be called off the main thread.
 */
final class MediaStorage {
    private MediaStorage() {}

    static String upload(String uid, String category, byte[] bytes, String mime, String ext)
            throws IOException {
        if (uid == null || uid.isEmpty()) throw new IOException("Not signed in");
        try {
            StorageReference ref = FirebaseStorage.getInstance().getReference()
                    .child("media").child(uid).child(category)
                    .child(UUID.randomUUID().toString().replace("-", "") + "." + ext);
            StorageMetadata md = new StorageMetadata.Builder().setContentType(mime).build();
            Tasks.await(ref.putBytes(bytes, md), 90, TimeUnit.SECONDS);
            Uri url = Tasks.await(ref.getDownloadUrl(), 30, TimeUnit.SECONDS);
            return url.toString();
        } catch (Exception e) {
            throw new IOException("Upload failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Deletes a file previously uploaded here. URLs from the old PHP host are ignored. */
    static void deleteByUrl(String url) throws IOException {
        if (url == null || !url.contains("firebasestorage.googleapis.com")) return;
        try {
            Tasks.await(FirebaseStorage.getInstance().getReferenceFromUrl(url).delete(),
                    30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Delete failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
