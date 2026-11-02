package com.silu.minigram;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.util.Log;
import android.view.*;
import android.view.animation.OvershootInterpolator;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.io.*;
import java.text.*;
import java.util.*;
import org.json.*;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class ChatActivity extends AppCompatActivity {

    public static String currentChatId = null;

    // ─── State ───────────────────────────────────────────────────────────────
    private String chatId, peerUid, peerName, peerPhoto, myUid;
    private String replyToId = null, replyPreview = null;
    private Set<String>  selectedMsgs = new HashSet<>();
    private ActionMode   mActionMode;
    private String       highlightedMsgId = null;

    // accessToken / projectId fields removed — no longer needed client-side.
    private String  peerFcmToken = "";
    /** Peer has published E2EE keys (or this chat is already encrypted): media is encrypted before upload. */
    private boolean useE2ee = false;
    private String  pendingMediaKey = null;
    private ValueEventListener peerTokenListener = null;
    private boolean isMuted      = false;
    private boolean isFirstLoad  = true;
    // Requirement 3 (Phase 3) — MyProfileActivity's Media/Links/Voice "View"
    // action lands here via the scroll_to_msg_id extra. Only works if the
    // target message is within the initial page (limitToLast); a very old
    // message beyond that isn't found by this — pagination-aware seeking
    // to an arbitrary historical message is a separate, larger feature.
    private String pendingScrollMsgId = null;
    // Interstitial shown on chat-exit, capped so it doesn't fire every single time.
    private final InterstitialAdManager exitInterstitialAd =
        InterstitialAdManager.forChatExit();
    // ── Pagination ──────────────────────────────────────────────────────────
    private static final int MESSAGES_PAGE_SIZE = 50;
    private int messagesPageSize = MESSAGES_PAGE_SIZE; // grows by a page each time user scrolls up
    private boolean isLoadingMore = false;
    private long totalMessageCount = -1; // -1 = unknown; set once we know the chat's real size

    private FirebaseAuth      auth;
    private FirebaseDatabase  database;
    private DatabaseReference messagesRef, userChatsRef;
    private ValueEventListener messagesListener, statusListener;

    // ─── Typing ──────────────────────────────────────────────────────────────
    private ValueEventListener typingListener;
    private final Handler  typingHandler      = new Handler(Looper.getMainLooper());
    private       Runnable stopTypingRunnable = null;
    private       boolean  isCurrentlyTyping  = false;
    private       boolean  isPeerTyping       = false;

    // ─── New-Message Bubble ───────────────────────────────────────────────────
    private final Set<String> knownMsgIds  = new HashSet<>();
    private       TextView    newMsgBubble;
    private       int         newMsgCount   = 0;
    private       String      firstNewMsgId = null;
    private       boolean     isAtBottom    = true;

    // ─────────────────────────────────────────────────────────────────────────
    //  Instant Upload
    // ─────────────────────────────────────────────────────────────────────────
    private final HashMap<String, Uri> pendingRetryUris = new HashMap<>();
    private final HashMap<String, File> pendingRetryVoiceFiles = new HashMap<>();
    private final UploadRetryQueue retryQueue = new UploadRetryQueue();
    private ConnectionStateMonitor connectionMonitor;

    // ─── Views ───────────────────────────────────────────────────────────────
    private LinearLayout toolbar, linear37, linear97, linear98, linear18;
    // ── Voice message recording ─────────────────────────────────────────────
    private LinearLayout layout_recording_bar, layout_slide_to_cancel;
    private TextView     dot_recording, txt_recording_timer;
    private android.animation.ObjectAnimator dotPulseAnimator;
    private android.media.MediaRecorder mediaRecorder;
    private String  voiceFilePath   = null;
    private boolean isRecording     = false;
    private boolean recordingCancelled = false;
    private long    recordingStartTime = 0L;
    private float   slideStartX = 0f;
    private final Handler recordingTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable recordingTimerRunnable;
    private static final int REQ_RECORD_AUDIO = 4321;
    private RecyclerView listview1;
    private LinearLayoutManager chatLayoutManager;
    private ProgressBar  progressLoadMore;
    private LinearLayout layoutInitialLoading;
    private TextView     btn_back_icon;
    private ImageView    img_profile;
    private TextView     txt_name, more_options, txt_status;
    private LinearLayout btn_send;
    private TextView     btn_attach_icon, btn_send_icon;
    private EditText     edit_msg;
    private LinearLayout layout_reply_bar;
    private LinearLayout layoutComposeLinkPreview;
    private ImageView    imgComposeLinkPreview;
    private ProgressBar  progressComposeLinkPreview;
    private TextView     txtComposeLinkPreviewTitle, txtComposeLinkPreviewDesc;
    private LinkPreviewHelper.LinkPreview composedPreview = null; // what's currently shown/ready to embed
    private String composedPreviewUrl = null; // which URL it belongs to
    private final Handler composePreviewHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingComposePreviewCheck;
    private TextView     txt_reply_preview, btn_cancel_reply;
    private LinearLayout pinned_bar;
    private TextView     pinned_text_btn, btn_unpin;
    // ── In-chat search ──────────────────────────────────────────────────────
    private LinearLayout search_bar;
    private EditText     edit_search;
    private TextView     txt_search_count, btn_search_prev, btn_search_next, btn_search_close, btn_search_chat;
    private final java.util.List<Integer> searchMatchPositions = new java.util.ArrayList<>();
    private int searchCurrentIndex = -1;

    private ArrayList<HashMap<String, Object>> messageList = new ArrayList<>();
    private MessageCacheHelper msgCache;
    private ChatMessageRecyclerAdapter adapter;

    @Override
    protected void onCreate(Bundle _savedInstanceState) {
        super.onCreate(_savedInstanceState);
        EdgeToEdgeHelper.setup(this);
        setContentView(R.layout.chat);
        {
            // Status bar/side insets go on the root; the bottom inset (nav bar / keyboard) goes INTO the solid input bar,
            // so the wallpaper never shows through underneath it.
            final View root = findViewById(R.id.main), bar = findViewById(R.id.linear37);
            final int barPadBottom = bar.getPaddingBottom();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, wi) -> {
                androidx.core.graphics.Insets sys = wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                androidx.core.graphics.Insets ime = wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
                v.setPadding(sys.left, sys.top, sys.right, 0);
                bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(), bar.getPaddingRight(), barPadBottom + Math.max(sys.bottom, ime.bottom));
                return wi;
            });
        }
        initialize(_savedInstanceState);
        FirebaseApp.initializeApp(this);
        initializeLogic();
        com.google.android.gms.ads.MobileAds.initialize(this, initStatus -> {});
        exitInterstitialAd.preload(this);

        // IMPORTANT: the legacy @Override onBackPressed() below does NOT
        // reliably fire on Android 13+ devices with predictive back gesture
        // enabled (very common on OPPO/Realme/OnePlus ColorOS) — swiping back
        // is handled by the newer OnBackPressedDispatcher instead, which
        // bypasses onBackPressed() entirely unless the app also registers a
        // callback here. This was the actual reason the interstitial's exit
        // logic never ran on that device, regardless of gesture vs button.
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                _handleBackPress();
            }
        });

        connectionMonitor = new ConnectionStateMonitor(this, () -> retryQueue.retryAllNow());
        connectionMonitor.start();
    }

    private void initialize(Bundle _savedInstanceState) {
        toolbar          = findViewById(R.id.toolbar);
        listview1        = findViewById(R.id.listview1);
        // RecyclerView needs an explicit LayoutManager (ListView never did).
        // stackFromEnd(true) replicates the old stackFromBottom="true" +
        // transcriptMode="alwaysScroll" combo — new items append and the
        // view stays pinned to the bottom, chat-app style.
        chatLayoutManager = new LinearLayoutManager(this);
        chatLayoutManager.setStackFromEnd(true);
        listview1.setLayoutManager(chatLayoutManager);
        progressLoadMore     = findViewById(R.id.progress_load_more);
        layoutInitialLoading = findViewById(R.id.layout_initial_loading);
        linear37         = findViewById(R.id.linear37);
        linear18         = findViewById(R.id.linear18);
        btn_back_icon    = findViewById(R.id.btn_back_icon);
        more_options     = findViewById(R.id.more_options);
        linear97         = findViewById(R.id.linear97);
        img_profile      = findViewById(R.id.img_profile);
        txt_name         = findViewById(R.id.txt_name);
        txt_status       = findViewById(R.id.txt_status);
        linear98         = findViewById(R.id.linear98);
        btn_send         = findViewById(R.id.btn_send);
        btn_attach_icon  = findViewById(R.id.btn_attach_icon);
        edit_msg         = findViewById(R.id.edit_msg);
        btn_send_icon    = findViewById(R.id.btn_send_icon);
        layout_reply_bar = findViewById(R.id.layout_reply_bar);
        layoutComposeLinkPreview     = findViewById(R.id.layout_compose_link_preview);
        imgComposeLinkPreview        = findViewById(R.id.img_compose_link_preview);
        progressComposeLinkPreview   = findViewById(R.id.progress_compose_link_preview);
        txtComposeLinkPreviewTitle   = findViewById(R.id.txt_compose_link_preview_title);
        txtComposeLinkPreviewDesc    = findViewById(R.id.txt_compose_link_preview_desc);
        TextView btnCancelComposePreview = findViewById(R.id.btn_cancel_compose_preview);
        if (btnCancelComposePreview != null) {
            btnCancelComposePreview.setOnClickListener(v -> {
                composedPreview = null;
                composedPreviewUrl = null;
                if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);
            });
        }
        pinned_bar       = findViewById(R.id.pinned_bar);
        search_bar       = findViewById(R.id.search_bar);
        edit_search      = findViewById(R.id.edit_search);
        txt_search_count = findViewById(R.id.txt_search_count);
        btn_search_prev  = findViewById(R.id.btn_search_prev);
        btn_search_next  = findViewById(R.id.btn_search_next);
        btn_search_close = findViewById(R.id.btn_search_close);
        btn_search_chat  = findViewById(R.id.btn_search_chat);
        pinned_text_btn  = findViewById(R.id.pinned_text);
        btn_unpin        = findViewById(R.id.btn_unpin);
        txt_reply_preview = layout_reply_bar != null
            ? layout_reply_bar.findViewById(R.id.txt_reply_preview) : null;
        btn_cancel_reply  = layout_reply_bar != null
            ? layout_reply_bar.findViewById(R.id.btn_cancel_reply) : null;
        layout_recording_bar = findViewById(R.id.layout_recording_bar);
        dot_recording         = findViewById(R.id.dot_recording);
        txt_recording_timer   = findViewById(R.id.txt_recording_timer);
        layout_slide_to_cancel = findViewById(R.id.layout_slide_to_cancel);

        btn_back_icon.setOnClickListener(v -> onBackPressed());

        // Tap-based voice notes, Telegram-style: tap mic to start recording
        // (hands-free from the first tap — no press-and-hold needed), tap the
        // same button again (now showing "send") to stop and send. Dragging
        // the recording bar left past a threshold cancels it.
        btn_send.setOnClickListener(v -> {
            String text = edit_msg.getText().toString().trim();
            if (!text.isEmpty()) _processAndSendText(text);        // mic gestures are handled by the touch listener below
        });
        btn_send.setOnTouchListener(this::_onMicTouch);

        TextView trash = findViewById(R.id.btn_rec_trash);
        TextView pause = findViewById(R.id.btn_rec_pause);
        if (trash != null) trash.setOnClickListener(v -> _stopVoiceRecording(true));
        if (pause != null) pause.setOnClickListener(v -> _togglePauseRecording());

        btn_attach_icon.setOnClickListener(v -> _openAttachSheet());
        View stickerBtn = findViewById(R.id.btn_sticker);
        if (stickerBtn != null) stickerBtn.setOnClickListener(v -> _openStickers());

        if (btn_cancel_reply != null) btn_cancel_reply.setOnClickListener(v -> _clearReply());
        if (more_options     != null) more_options.setOnClickListener(v -> showHeaderMenu(more_options));
        _setupChatSearch();
        img_profile.setOnClickListener(v -> _showFullscreenPeerPhoto());
        linear97.setOnClickListener(v -> showHeaderMenu(linear97));

        listview1.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                int first = chatLayoutManager.findFirstVisibleItemPosition();
                int last  = chatLayoutManager.findLastVisibleItemPosition();
                int total = adapter.getItemCount();
                boolean atBottom = total > 0 && last >= total - 1;
                // DIAGNOSTIC: filter logcat with "MinigramScroll" to see every
                // isAtBottom transition in real time while scrolling/testing.
                if (atBottom != isAtBottom) {
                    Log.d("MinigramScroll", "Chat isAtBottom " + isAtBottom + " -> " + atBottom
                            + "  (last=" + last + " total=" + total + ")");
                }
                if (atBottom && !isAtBottom) _hideBubble();
                isAtBottom = atBottom;

                // Reached the top and there may be older messages → load one more page
                if (first == 0 && total > 0 && !isFirstLoad && !isLoadingMore
                        && (totalMessageCount < 0 || messagesPageSize < totalMessageCount)) {
                    _loadOlderMessages();
                }
            }
        });

        _createNewMsgBubble();
    }

    // ------------------------------------------------------------------ attachments: video / audio / documents / stickers
    private String pendingPickKind;
    private final androidx.activity.result.ActivityResultLauncher<String[]> filePicker =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null && pendingPickKind != null) _handlePickedFile(uri, pendingPickKind);
        });
    private final HashMap<String, Object[]> pendingRetryFiles = new HashMap<>();   // tempId -> {Uri, kind}

    private void _openAttachSheet() {
        AttachSheet s = new AttachSheet();
        s.setPicker(what -> {
            switch (what) {
                case "gallery": _openMediaPicker(); break;
                case "sticker": _openStickers(); break;
                case "video":    pendingPickKind = "video";    filePicker.launch(new String[]{"video/*"}); break;
                case "audio":    pendingPickKind = "audio";    filePicker.launch(new String[]{"audio/*"}); break;
                default:         pendingPickKind = "document"; filePicker.launch(new String[]{
                    "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.*",
                    "application/vnd.ms-excel", "application/vnd.ms-powerpoint", "text/*", "application/zip",
                    "application/rtf", "application/vnd.oasis.opendocument.*"}); break;
            }
        });
        s.show(getSupportFragmentManager(), "attach");
    }

    private void _openStickers() {
        StickerSheet s = new StickerSheet();
        s.setCallback(id -> _sendMessage("sticker", id, "", "", false));
        s.show(getSupportFragmentManager(), "stickers");
    }

    private void _handlePickedFile(final Uri uri, final String kind) {
        new Thread(() -> {
            String name = "file", mime = null; long size = 0; int dur = 0;
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
                    if (si >= 0) size = c.getLong(si);
                }
            } catch (Exception ignored) {}
            mime = getContentResolver().getType(uri);
            if (!"document".equals(kind)) {
                try {
                    android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                    r.setDataSource(this, uri);
                    String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) dur = (int) (Long.parseLong(d) / 1000);
                    r.release();
                } catch (Exception ignored) {}
            }
            final String err = FileLimits.check(kind, name, size, dur);
            final String fName = name, fMime = mime != null ? mime : "application/octet-stream";
            final long fSize = size; final int fDur = dur;
            runOnUiThread(() -> {
                if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
                _startFileUpload(uri, kind, fName, fMime, fSize, fDur, null);
            });
        }).start();
    }

    private static String _fileMetaJson(String name, long size, String kind, String mime, int dur) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("n", name); o.put("s", size); o.put("k", kind); o.put("m", mime); o.put("d", dur);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    private void _startFileUpload(final Uri uri, final String kind, final String name, final String mime,
                                  final long size, final int dur, String reuseTempId) {
        final String tempId = reuseTempId != null ? reuseTempId : "temp_" + System.currentTimeMillis();
        final String meta = _fileMetaJson(name, size, kind, mime, dur);
        if (reuseTempId == null) {
            HashMap<String, Object> tempMsg = new HashMap<>();
            tempMsg.put("msgId", tempId); tempMsg.put("sender_uid", myUid); tempMsg.put("type", "file");
            tempMsg.put("image_url", ""); tempMsg.put("local_uri", uri.toString());
            tempMsg.put("caption", meta); tempMsg.put("text", "");
            tempMsg.put("timestamp", System.currentTimeMillis());
            tempMsg.put("is_edited", false); tempMsg.put("is_pinned", false); tempMsg.put("is_forwarded", false);
            tempMsg.put("is_deleted_for_all", false); tempMsg.put("status", "pending"); tempMsg.put("upload_state", "uploading");
            messageList.add(tempMsg);
            adapter.refreshWithoutScroll();
            listview1.post(() -> listview1.scrollToPosition(adapter.getItemCount() - 1));
            isAtBottom = true;
        } else _setTempMsgState(tempId, "uploading");
        pendingRetryFiles.put(tempId, new Object[]{uri, kind});
        final boolean enc = useE2ee;
        new Thread(() -> {
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    byte[] buf = new byte[16384]; int n; long total = 0;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > Math.max(FileLimits.VIDEO_MAX_BYTES, FileLimits.DOC_MAX_BYTES)) throw new java.io.IOException("File too large");
                        bo.write(buf, 0, n);
                    }
                }
                byte[] bytes = bo.toByteArray();
                String key = null;
                String contentType = mime;
                String ext = FileLimits.ext(name);
                if (enc) {
                    com.silu.minigram.e2ee.E2ee.SealedMedia sealed = com.silu.minigram.e2ee.E2ee.sealMedia(bytes);
                    bytes = sealed.data; key = sealed.keyB64; contentType = "application/octet-stream"; ext = "bin";
                }
                String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
                final String url = MediaStorage.upload(uid, "files", bytes, contentType, ext.isEmpty() ? "bin" : ext);
                final String fKey = key;
                runOnUiThread(() -> {
                    _removeTempMsg(tempId);
                    pendingRetryFiles.remove(tempId);
                    pendingMediaKey = fKey;
                    _sendMessage("file", "", url, meta, false);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    _setTempMsgState(tempId, "failed");
                    Toast.makeText(ChatActivity.this, "Upload failed: " + (e.getMessage() != null ? e.getMessage() : "network error"), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void _openMediaPicker() {
        TelegramMediaPicker picker = TelegramMediaPicker.newInstance();
        picker.setListener((uriStrings, caption) -> {
            for (String uriStr : uriStrings) {
                _sendImageInstant(Uri.parse(uriStr), caption);
            }
        });
        picker.show(getSupportFragmentManager(), "media_picker");
    }

    private void _sendImageInstant(Uri imageUri, String caption) {
        if (imageUri == null) return;

        String tempId = "temp_" + System.currentTimeMillis();
        long   ts     = System.currentTimeMillis();

        String savedReplyId     = replyToId;
        String savedReplyPreview= replyPreview;
        String savedReplySender = getReplySenderLabel(replyToId, peerName);

        HashMap<String, Object> tempMsg = new HashMap<>();
        tempMsg.put("msgId",              tempId);
        tempMsg.put("sender_uid",         myUid);
        tempMsg.put("type",               "image");
        tempMsg.put("image_url",          "");
        tempMsg.put("local_uri",          imageUri.toString());
        tempMsg.put("caption",            caption != null ? caption : "");
        tempMsg.put("text",               "");
        tempMsg.put("timestamp",          ts);
        tempMsg.put("is_edited",          false);
        tempMsg.put("is_pinned",          false);
        tempMsg.put("is_forwarded",       false);
        tempMsg.put("is_deleted_for_all", false);
        tempMsg.put("status",             "pending");
        tempMsg.put("upload_state",       "uploading");

        if (savedReplyId != null) {
            tempMsg.put("reply_to_id",   savedReplyId);
            tempMsg.put("reply_preview", savedReplyPreview);
            tempMsg.put("reply_sender",  savedReplySender);
        }

        pendingRetryUris.put(tempId, imageUri);

        messageList.add(tempMsg);
        adapter.refreshWithoutScroll();
        listview1.post(() -> listview1.scrollToPosition(adapter.getItemCount() - 1));
        isAtBottom = true;
        _clearReply();

        String finalCaption = caption != null ? caption : "";
        _startBackgroundUpload(tempId, imageUri, finalCaption,
            savedReplyId, savedReplyPreview, savedReplySender);
    }

    private void _startBackgroundUpload(String tempId, Uri uri, String caption,
                                         String replyId, String replyPreview, String replySender) {
        ImageEditHelper.compressImage(this, uri, new ImageEditHelper.CompressCallback() {
            @Override public void onSuccess(Uri compressed) {
                _doUpload(tempId, compressed, caption, replyId, replyPreview, replySender);
            }
            @Override public void onError(String e) {
                _doUpload(tempId, uri, caption, replyId, replyPreview, replySender);
            }
        });
    }

    private void _doUpload(String tempId, Uri uri, String caption,
                             String replyId, String replyPrev, String replySender) {
        if (useE2ee) {
            UploadHelper.uploadImageEncrypted(this, uri, new UploadHelper.EncCallback() {
                @Override public void onSuccess(String url, String keyB64) {
                    runOnUiThread(() -> {
                        retryQueue.onSuccess(tempId);
                        _removeTempMsg(tempId);
                        pendingRetryUris.remove(tempId);
                        pendingMediaKey = keyB64;
                        _pushImageToFirebase(url, caption, replyId, replyPrev, replySender);
                    });
                }
                @Override public void onError(String err) {
                    runOnUiThread(() -> _setTempMsgState(tempId, "failed"));
                }
            });
            return;
        }
        UploadHelper.uploadImage(this, uri, new UploadHelper.UploadCallback() {
            @Override
            public void onSuccess(String url) {
                runOnUiThread(() -> {
                    retryQueue.onSuccess(tempId);
                    _removeTempMsg(tempId);
                    pendingRetryUris.remove(tempId);
                    _pushImageToFirebase(url, caption, replyId, replyPrev, replySender);
                });
            }
            @Override
            public void onError(String err) {
                runOnUiThread(() -> _setTempMsgState(tempId, "failed"));
            }
        });
    }

    private void _removeTempMsg(String tempId) {
        if (tempId == null || !tempId.startsWith("temp_")) return;
        for (int i = 0; i < messageList.size(); i++) {
            if (tempId.equals(messageList.get(i).get("msgId"))) {
                messageList.remove(i);
                adapter.refreshWithoutScroll();
                return;
            }
        }
    }

    private void _setTempMsgState(String tempId, String newState) {
        for (HashMap<String, Object> msg : messageList) {
            if (tempId.equals(msg.get("msgId"))) {
                msg.put("upload_state", newState);
                adapter.refreshWithoutScroll();
                if ("failed".equals(newState)) {
                    retryQueue.enqueue(tempId, () -> _performRetry(tempId));
                }
                return;
            }
        }
    }

    /** Shared by manual tap-to-retry AND the automatic backoff queue — exactly one retry code path. */
    private void _performRetry(String tempId) {
        if (pendingRetryFiles.containsKey(tempId)) {
            Object[] f = pendingRetryFiles.get(tempId);
            try {
                org.json.JSONObject o = null;
                for (HashMap<String, Object> m : messageList) if (tempId.equals(m.get("msgId"))) { o = new org.json.JSONObject(String.valueOf(m.get("caption"))); break; }
                if (o != null) { _startFileUpload((Uri) f[0], (String) f[1], o.optString("n"), o.optString("m"), o.optLong("s"), o.optInt("d"), tempId); return; }
            } catch (Exception ignored) {}
        }
        if (pendingRetryVoiceFiles.containsKey(tempId)) _retryVoiceUpload(tempId);
        else _retryUpload(tempId);
    }

    /**
     * Live link preview while composing — Telegram-style. Debounced so it
     * doesn't fire a network request on every keystroke; only fetches once
     * the URL in the text actually settles. What's shown here IS what gets
     * embedded into the message on send (see _sendMessage()) — so the
     * chat bubble never has to fetch this again, for anyone.
     */
    private void _scheduleComposeLinkPreview(String text) {
        String url = LinkPreviewHelper.extractFirstUrl(text);

        if (url == null) {
            if (pendingComposePreviewCheck != null) composePreviewHandler.removeCallbacks(pendingComposePreviewCheck);
            composedPreview = null;
            composedPreviewUrl = null;
            if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);
            return;
        }

        if (url.equals(composedPreviewUrl)) return; // already fetched (or fetching) this exact URL

        if (pendingComposePreviewCheck != null) composePreviewHandler.removeCallbacks(pendingComposePreviewCheck);
        composedPreview = null;
        composedPreviewUrl = url;

        pendingComposePreviewCheck = () -> {
            if (layoutComposeLinkPreview == null) return;
            layoutComposeLinkPreview.setVisibility(View.VISIBLE);
            if (progressComposeLinkPreview != null) progressComposeLinkPreview.setVisibility(View.VISIBLE);
            if (imgComposeLinkPreview != null) imgComposeLinkPreview.setVisibility(View.GONE);
            if (txtComposeLinkPreviewTitle != null) txtComposeLinkPreviewTitle.setText("Fetching preview…");
            if (txtComposeLinkPreviewDesc != null) txtComposeLinkPreviewDesc.setText("");

            LinkPreviewHelper.fetch(url, preview -> {
                // The text may have changed again while this fetch was in
                // flight — only apply the result if it's still relevant.
                if (!url.equals(composedPreviewUrl)) return;
                if (progressComposeLinkPreview != null) progressComposeLinkPreview.setVisibility(View.GONE);

                if (preview == null) {
                    if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);
                    composedPreviewUrl = null;
                    return;
                }
                composedPreview = preview;
                if (txtComposeLinkPreviewTitle != null)
                    txtComposeLinkPreviewTitle.setText(preview.title != null ? preview.title : url);
                if (txtComposeLinkPreviewDesc != null)
                    txtComposeLinkPreviewDesc.setText(preview.description != null ? preview.description : "");
                if (preview.imageUrl != null && !preview.imageUrl.isEmpty() && imgComposeLinkPreview != null) {
                    imgComposeLinkPreview.setVisibility(View.VISIBLE);
                    Glide.with(this).load(preview.imageUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .into(imgComposeLinkPreview);
                }
            });
        };
        composePreviewHandler.postDelayed(pendingComposePreviewCheck, 600);
    }

    private void _retryVoiceUpload(String tempId) {
        File audioFile = pendingRetryVoiceFiles.get(tempId);
        if (audioFile == null || !audioFile.exists()) {
            Toast.makeText(this, "Cannot retry \u2014 recording no longer available", Toast.LENGTH_SHORT).show();
            return;
        }
        int durationSec = 0;
        for (HashMap<String, Object> msg : messageList) {
            if (tempId.equals(msg.get("msgId"))) {
                Object d = msg.get("duration");
                if (d instanceof Number) durationSec = ((Number) d).intValue();
                msg.put("upload_state", "uploading");
                adapter.refreshWithoutScroll();
                break;
            }
        }
        final int fDuration = durationSec;
        _uploadVoice(tempId, audioFile, fDuration);
    }

    private void _retryUpload(String tempId) {
        Uri originalUri = pendingRetryUris.get(tempId);
        if (originalUri == null) {
            Toast.makeText(this, "Cannot retry \u2014 source no longer available", Toast.LENGTH_SHORT).show();
            return;
        }
        for (HashMap<String, Object> msg : messageList) {
            if (tempId.equals(msg.get("msgId"))) {
                msg.put("upload_state", "uploading");
                adapter.refreshWithoutScroll();

                String caption    = (String) msg.get("caption");
                String replyId    = (String) msg.get("reply_to_id");
                String replyPrev  = (String) msg.get("reply_preview");
                String replySender= (String) msg.get("reply_sender");

                _doUpload(tempId, originalUri,
                    caption     != null ? caption     : "",
                    replyId,
                    replyPrev   != null ? replyPrev   : "",
                    replySender != null ? replySender : "");
                return;
            }
        }
    }

    private void _pushImageToFirebase(String imageUrl, String caption,
                                       String altReplyId, String altReplyPreview,
                                       String altReplySender) {
        String msgId = messagesRef.push().getKey();
        if (msgId == null) return;
        long ts = System.currentTimeMillis();

        HashMap<String, Object> msg = new HashMap<>();
        msg.put("sender_uid",         myUid);
        msg.put("type",               "image");
        msg.put("text",               "");
        msg.put("image_url",          imageUrl);
        msg.put("caption",            caption);
        msg.put("timestamp",          ts);
        msg.put("is_edited",          false);
        msg.put("is_pinned",          false);
        msg.put("is_forwarded",       false);
        msg.put("is_deleted_for_all", false);
        msg.put("is_deleted_for_me",  false);
        msg.put("status",             "sent");

        if (altReplyId != null && !altReplyId.isEmpty()) {
            msg.put("reply_to_id",   altReplyId);
            msg.put("reply_preview", altReplyPreview);
            msg.put("reply_sender",  altReplySender);
        }

        // Same fix as _sendMessage(): don't pre-register the id in
        // knownMsgIds, or the confirmed message reads as already-seen and
        // the scroll-to-my-new-message branch never fires.
        _commitMessage(msgId, msg, "\uD83D\uDCF7 Photo", ts, imageUrl);
    }

    private void _createNewMsgBubble() {
        newMsgBubble = new TextView(this);
        newMsgBubble.setVisibility(View.GONE);
        newMsgBubble.setTextColor(Color.WHITE);
        newMsgBubble.setTextSize(13f);
        newMsgBubble.setTypeface(null, Typeface.BOLD);
        newMsgBubble.setPadding(dp(18), dp(10), dp(18), dp(10));
        newMsgBubble.setGravity(Gravity.CENTER);

        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(dp(30));
        pill.setColor(Ink.c("#075E54"));
        newMsgBubble.setBackground(pill);
        newMsgBubble.setElevation(dp(8));

        newMsgBubble.setOnClickListener(v -> {
            String targetId = firstNewMsgId;
            _hideBubble();
            if (targetId != null) scrollToMessage(targetId);
            else listview1.post(() -> listview1.scrollToPosition(adapter.getItemCount() - 1));
        });

        final ViewGroup root = (ViewGroup) getWindow().getDecorView();
        root.post(() -> {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            int bottomMargin = dp(88);
            if (linear98 != null && linear98.getHeight() > 0)
                bottomMargin = linear98.getHeight() + dp(14);
            lp.bottomMargin = bottomMargin;
            root.addView(newMsgBubble, lp);
        });
    }

    private void _showBubble() {
        // DIAGNOSTIC: agar ye null hua, matlab _createNewMsgBubble() abhi
        // tak view attach nahi kar paya (root.post() abhi execute nahi hua) —
        // yehi ek real candidate hai jo bubble ko chup-chaap no-op bana sakta hai.
        Log.d("MinigramScroll", "_showBubble() called, newMsgBubble null? " + (newMsgBubble == null));
        if (newMsgBubble == null) return;
        runOnUiThread(() -> {
            Log.d("MinigramScroll", "_showBubble() setting VISIBLE, count=" + newMsgCount);
            String label = newMsgCount == 1
                ? "\u2193   1 New Message"
                : "\u2193   " + newMsgCount + " New Messages";
            newMsgBubble.setText(label);
            if (newMsgBubble.getVisibility() != View.VISIBLE) {
                newMsgBubble.setVisibility(View.VISIBLE);
                newMsgBubble.setAlpha(0f);
                newMsgBubble.setScaleX(0.5f);
                newMsgBubble.setScaleY(0.5f);
                newMsgBubble.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(new OvershootInterpolator(1.4f))
                    .start();
            } else {
                newMsgBubble.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80)
                    .withEndAction(() ->
                        newMsgBubble.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
                    .start();
            }
        });
    }

    private void _hideBubble() {
        if (newMsgBubble == null || newMsgBubble.getVisibility() != View.VISIBLE) return;
        newMsgBubble.animate()
            .alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(180)
            .withEndAction(() -> {
                newMsgBubble.setVisibility(View.GONE);
                newMsgBubble.setAlpha(1f);
                newMsgCount   = 0;
                firstNewMsgId = null;
            }).start();
    }

    private void _resetBubbleState() {
        newMsgCount   = 0;
        firstNewMsgId = null;
        isAtBottom    = true;
        knownMsgIds.clear();
        if (newMsgBubble != null) {
            newMsgBubble.setVisibility(View.GONE);
            newMsgBubble.setAlpha(1f);
            newMsgBubble.setScaleX(1f);
            newMsgBubble.setScaleY(1f);
        }
    }

    @Override
    public void onBackPressed() {
        // Kept for compatibility with older Android versions / physical back
        // button; gesture-based back on newer devices goes through the
        // OnBackPressedCallback registered in onCreate() instead, which
        // calls this same shared method.
        _handleBackPress();
    }

    private void _handleBackPress() {
        Log.d("InterstitialAd", "[chat_exit] _handleBackPress() fired — search_bar visible="
            + (search_bar != null && search_bar.getVisibility() == View.VISIBLE));
        if (search_bar != null && search_bar.getVisibility() == View.VISIBLE) {
            _closeSearch();
            return;
        }
        Log.d("InterstitialAd", "[chat_exit] calling maybeShow() now");
        exitInterstitialAd.maybeShow(this, () -> {
            if (isTaskRoot()) {
                startActivity(new Intent(this, ChatlistActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
            finish();
        });
    }

    private void showHeaderMenu(View anchor) {
        InkMenu.show(this, anchor,
            new String[]{"person_outline", isMuted ? "notifications" : "notifications_off", "file_download", "lock_outline"},
            new String[]{"View profile", isMuted ? "Unmute" : "Mute", "Export chat", "Verify encryption"},
            i -> {
                if (i == 0) _showPeerProfile();
                else if (i == 1) _toggleMute();
                else if (i == 2) showExportDialog();
                else _openVerify();
            });
    }

    private void _toggleMute() {
        isMuted = !isMuted;
        getSharedPreferences("MutedChats", MODE_PRIVATE).edit()
            .putBoolean(chatId, isMuted).apply();
        Toast.makeText(this, isMuted ? "Chat muted" : "Chat unmuted", Toast.LENGTH_SHORT).show();
    }

    private void _showFullscreenPeerPhoto() {
        if (peerPhoto != null && !peerPhoto.isEmpty())
            startActivity(new Intent(this, ImageViewActivity.class).putExtra("image_url", peerPhoto));
        else _showPeerProfile();
    }

    private void showExportDialog() {
        new AlertDialog.Builder(this).setTitle("Export Chat")
            .setMessage("Include media links?")
            .setPositiveButton("With Media",    (d, w) -> exportChatData(true))
            .setNegativeButton("Without Media", (d, w) -> exportChatData(false))
            .setNeutralButton("Cancel", null).show();
    }

    private void exportChatData(boolean includeMedia) {
        Toast.makeText(this, "Exporting...", Toast.LENGTH_SHORT).show();
        org.json.JSONArray arr = new org.json.JSONArray();
        for (HashMap<String, Object> msg : messageList) {
            String mId = (String) msg.get("msgId");
            if (mId != null && mId.startsWith("temp_")) continue;
            try {
                JSONObject obj = new JSONObject();
                String sender = myUid.equals(msg.get("sender_uid")) ? "Me" : peerName;
                obj.put("sender", sender);
                Long ts = (Long) msg.get("timestamp");
                obj.put("timestamp", ts != null
                    ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ts)) : "");
                obj.put("type", msg.get("type"));
                if (includeMedia) {
                    String iu = (String) msg.get("image_url");
                    if (iu != null && !iu.isEmpty()) obj.put("media_url", iu);
                }
                String t = (String) msg.get("text");
                String c = (String) msg.get("caption");
                if (t != null && !t.isEmpty()) obj.put("text", t);
                if (c != null && !c.isEmpty()) obj.put("caption", c);
                arr.put(obj);
            } catch (JSONException ignored) {}
        }
        try {
            File f = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS),
                "Chat_Export_" + peerName + "_" + System.currentTimeMillis() + ".json");
            java.io.FileWriter fw = new java.io.FileWriter(f);
            try { fw.write(arr.toString(4)); } catch (JSONException e) { fw.write(arr.toString()); }
            fw.close();
            Toast.makeText(this, "Exported to Downloads!", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String getReplySenderLabel(String msgId, String fallback) {
        for (HashMap<String, Object> msg : messageList) {
            if (msgId != null && msgId.equals(msg.get("msgId"))) {
                String sUid = (String) msg.get("sender_uid");
                return myUid != null && myUid.equals(sUid) ? "You"
                    : (peerName != null && !peerName.isEmpty() ? peerName : fallback);
            }
        }
        return fallback != null ? fallback : "User";
    }

    private void initializeLogic() {
        chatId    = getIntent().getStringExtra("chat_id");
        peerUid   = getIntent().getStringExtra("peer_uid");
        peerName  = getIntent().getStringExtra("peer_name");
        peerPhoto = getIntent().getStringExtra("peer_photo");
        pendingScrollMsgId = getIntent().getStringExtra("scroll_to_msg_id");

        auth     = FirebaseAuth.getInstance();
        database = FirebaseDatabase.getInstance();
        myUid    = auth.getCurrentUser().getUid();

        // Defensive: a caller that passes peer_uid but forgets chat_id used
        // to crash the whole app in _continueInit() at
        // getReference("chats").child(null). The id is fully derivable from
        // the two uids (that's how every launcher builds it), so derive it
        // rather than letting one missing extra be fatal.
        if ((chatId == null || chatId.isEmpty()) && peerUid != null && !peerUid.isEmpty()) {
            chatId = myUid.compareTo(peerUid) < 0 ? myUid + "_" + peerUid : peerUid + "_" + myUid;
        }
        isMuted  = getSharedPreferences("MutedChats", MODE_PRIVATE).getBoolean(chatId != null ? chatId : "", false);

        if (peerUid == null || peerUid.isEmpty()) _resolvePeerFromChatId();
        else if (chatId == null || chatId.isEmpty()) {
            // Neither id nor peer — nothing this screen can meaningfully do.
            Toast.makeText(this, "Couldn't open this chat", Toast.LENGTH_SHORT).show();
            finish();
        }
        else _continueInit();
    }

    private void _resolvePeerFromChatId() {
        if (chatId == null) { finish(); return; }
        database.getReference("user_chats").child(myUid).child(chatId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    peerUid = snap.child("peer_uid").getValue(String.class);
                    if (peerUid == null || peerUid.isEmpty()) { finish(); return; }
                    database.getReference("users").child(peerUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot us) {
                                peerName  = us.child("fullname").getValue(String.class);
                                peerPhoto = us.child("photo_url").getValue(String.class);
                                if (peerName == null) peerName = "";
                                txt_name.setText(peerName);
                                // AvatarHelper falls back to generated initials
                                // when there's no photo, matching ChatlistActivity —
                                // this header used to show a generic grey person icon
                                // for anyone without a display picture.
                                AvatarHelper.load(ChatActivity.this, img_profile, peerPhoto, peerName);
                                _continueInit();
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                _continueInit();
                            }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finish(); }
            });
    }

    private void _continueInit() {
        txt_name.setText(peerName != null ? peerName : "");
        // Same as above — initials instead of a generic icon when the peer
        // has no photo set.
        AvatarHelper.load(this, img_profile, peerPhoto, peerName);

        messagesRef  = database.getReference("chats").child(chatId);
        userChatsRef = database.getReference("user_chats");
        _initEncryption();
        // NOTE: keepSynced(true) was removed here on purpose — it ignores
        // ANY query (including our limitToLast pagination below) and forces
        // Firebase to keep the ENTIRE chat history synced in the background
        // regardless of what's actually displayed. That silently defeated
        // the pagination further down: the UI only showed 50 messages, but
        // the full history was still being downloaded/cached behind the
        // scenes. Removing it means only the paginated window is fetched.

        // Know the chat's real size so scroll-up stops loading once we've reached the top
        messagesRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                totalMessageCount = snap.getChildrenCount();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });

        // FIX: token save ab FcmTokenManager karta hai (launcher se bhi call hota
        // hai), isliye har user ka token DB mein hota hai — sirf un logon ka nahi
        // jinhone kabhi chat kholi ho.
        // Purana FirebaseInstanceId API firebase-messaging 22+ mein hata diya gaya
        // hai; wo NoClassDefFoundError phenkta hai jo `catch (Exception)` pakadta
        // hi nahi, isliye token chup-chaap save hota hi nahi tha.
        FcmTokenManager.syncToken(this);

        // service_account.json / AccessTokenGenerator no longer used —
        // notifications now go through the secure Vercel backend instead.

        // FIX: onCancelled() pehle khaali tha, isliye Realtime Database ka
        // permission_denied bilkul "peer ke paas token hi nahi hai" jaisa dikhta
        // tha — 1-to-1 push ka sabse silent killer.
        // Peer FCM tokens are server-only now (fcm_tokens/{uid}); the sendNotification
        // Cloud Function resolves the receiver by uid.

        // ─── TYPING FIX: Firebase Rules override using user_chats ────────────
        edit_msg.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {
                _updateCommandDimming(s);
                // BUG FIXED: this used to live at the bottom of
                // onTextChanged(), BEHIND two unrelated early-returns — the
                // typing-indicator's `database/chatId/peerUid == null` guard
                // and the empty-text guard. So whether a link preview got
                // scheduled at all depended on typing-indicator state that
                // has nothing to do with previews. Pasting a URL could land
                // on a path that returned before ever reaching it, which is
                // why pasting showed nothing while then editing a character
                // appeared to "wake it up".
                //
                // afterTextChanged fires for EVERY change, paste included,
                // and this is unconditional now. Passing empty text is fine:
                // _scheduleComposeLinkPreview() hides the card in that case.
                _scheduleComposeLinkPreview(s.toString());
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (btn_send_icon != null) {
                    btn_send_icon.setText(s.toString().trim().isEmpty() ? "mic" : "send");
                }
                if (database == null || chatId == null || peerUid == null) return;
                
                if (s.toString().trim().isEmpty()) {
                    if (isCurrentlyTyping) {
                        isCurrentlyTyping = false;
                        if (stopTypingRunnable != null) typingHandler.removeCallbacks(stopTypingRunnable);
                        database.getReference("user_chats").child(peerUid).child(chatId).child("is_typing").setValue(false);
                    }
                    return;
                }

                if (!isCurrentlyTyping) {
                    isCurrentlyTyping = true;
                    database.getReference("user_chats").child(peerUid).child(chatId).child("is_typing").setValue(true)
                        .addOnFailureListener(e -> Log.e("MinigramTyping", "Failed to write is_typing=true", e));
                }
                if (stopTypingRunnable != null) typingHandler.removeCallbacks(stopTypingRunnable);
                stopTypingRunnable = () -> {
                    isCurrentlyTyping = false;
                    if (database != null && chatId != null && peerUid != null)
                        database.getReference("user_chats").child(peerUid).child(chatId).child("is_typing").setValue(false);
                };
                typingHandler.postDelayed(stopTypingRunnable, 2000);
            }
        });
        // Set the correct icon immediately on load — TextWatcher only fires on
        // future changes, not on initial (already-empty) state.
        if (btn_send_icon != null) {
            btn_send_icon.setText(edit_msg.getText().toString().trim().isEmpty() ? "mic" : "send");
        }

        adapter = new ChatMessageRecyclerAdapter(this, messageList, myUid, peerName, peerPhoto,
            listview1, selectedMsgs);
        _bindTranslateListener();
        _setupTranslateButton();
        adapter.setListener(new ChatMessageRecyclerAdapter.AdapterListener() {
            @Override public void onReplyClick(String msgId) { scrollToMessage(msgId); }

            @Override public void onImageClick(String msgId, String imgUrl) {
                if (imgUrl == null) { _showFullscreenPeerPhoto(); return; }
                if (mActionMode != null) { toggleSelection(msgId); return; }
                startActivity(new Intent(ChatActivity.this, ImageViewActivity.class)
                    .putExtra("image_url", imgUrl));
            }

            @Override public void onItemClick(HashMap<String, Object> msg) {
                final String mId = (String) msg.get("msgId");
                if (mActionMode != null) { toggleSelection(mId); return; }
                _showMsgActionSheet(msg);      // Telegram style: tap opens reactions + actions
            }

            @Override public void onItemLongClick(String msgId) {
                if (msgId != null && msgId.startsWith("temp_")) return;
                if (mActionMode != null) { toggleSelection(msgId); return; }
                listview1.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                selectedMsgs.add(msgId);
                mActionMode = startActionMode(mActionModeCallback);
                if (mActionMode != null) mActionMode.setTitle(selectedMsgs.size() + " selected");
                adapter.notifyMessageChanged(msgId);
            }

            @Override public void onSwipeReply(HashMap<String, Object> msg) {
                String id      = (String) msg.get("msgId");
                String ty = String.valueOf(msg.get("type"));
                String preview = "image".equals(ty) ? "\uD83D\uDCF7 Photo"
                    : "sticker".equals(ty) ? Stickers.label(String.valueOf(msg.get("text")))
                    : "file".equals(ty) ? _filePreview(String.valueOf(msg.get("caption")))
                    : "voice".equals(ty) ? "\uD83C\uDFA4 Voice message"
                    : (msg.get("text") != null ? (String) msg.get("text") : "");
                _setReplyMode(id, preview, getReplySenderLabel(id, peerName));
            }

            @Override public void onRetryUpload(String tempId) {
                retryQueue.cancel(tempId);
                _performRetry(tempId);
            }
        });

        listview1.setAdapter(adapter);

        // ── Instant load from local SQLite cache (before network responds) ────
        msgCache = MessageCacheHelper.get(this);
        if (chatId != null) {
            ArrayList<HashMap<String, Object>> cached = msgCache.loadMessages(chatId);
            if (!cached.isEmpty()) {
                messageList.clear();
                messageList.addAll(cached);
                for (HashMap<String, Object> m : cached) {
                    String mId = (String) m.get("msgId");
                    if (mId != null) knownMsgIds.add(mId);
                }
                adapter.refreshWithoutScroll();
                listview1.post(() -> listview1.scrollToPosition(adapter.getItemCount() - 1));
            }
        }

        messagesRef.orderByChild("is_pinned").equalTo(true).limitToLast(1)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (snap.exists()) {
                        for (DataSnapshot child : snap.getChildren()) {
                            String text = child.child("text").getValue(String.class);
                            if (text == null || text.isEmpty()) text = "\uD83D\uDCF7 Photo";
                            pinned_text_btn.setText(text);
                            pinned_bar.setVisibility(View.VISIBLE);
                            pinned_bar.setTag(child.getKey());
                            break;
                        }
                    } else pinned_bar.setVisibility(View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        pinned_bar.setOnClickListener(v -> {
            String id = (String) pinned_bar.getTag();
            if (id != null) scrollToMessage(id);
        });
        btn_unpin.setOnClickListener(v -> {
            String id = (String) pinned_bar.getTag();
            if (id != null) messagesRef.child(id).child("is_pinned").setValue(false);
        });

        if (getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
                .getBoolean(PrivacySecurityActivity.KEY_ONLINE_VIS, true))
            database.getReference("users").child(myUid).child("status").setValue("online");
        _listenMessages();
        _listenPeerStatus();
        _markMessagesRead();
        _restoreDraft();
    }

    // _loadFcmAccessToken() removed — notifications now go through the
    // secure Vercel backend (see FCMNotificationSender.sendNotification).
    // service_account.json is no longer bundled in this app at all.

    private void _loadOlderMessages() {
        isLoadingMore = true;
        if (progressLoadMore != null) progressLoadMore.setVisibility(View.VISIBLE);

        // Detach current listener, grow window by one page, re-attach
        if (messagesListener != null) {
            messagesRef.orderByChild("timestamp").limitToLast(messagesPageSize)
                .removeEventListener(messagesListener);
        }
        messagesPageSize += MESSAGES_PAGE_SIZE;

        // Reset first-load-style flags so the reload doesn't force-scroll to bottom
        isFirstLoad = false;
        knownMsgIds.clear();

        messagesListener = messagesRef.orderByChild("timestamp").limitToLast(messagesPageSize)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    _rebuildMessageList(snap, null, null);
                    // BUG FOUND during the RecyclerView migration: this never
                    // told the adapter to refresh, so the newly-loaded older
                    // messages wouldn't actually render until some unrelated
                    // event (like a new live message) forced a redraw.
                    //
                    // Also: the old ListView code manually remembered the
                    // pre-load item count and scrolled by the delta to
                    // compensate for new items being PREPENDED — that hack
                    // is no longer needed. RecyclerView's LinearLayoutManager
                    // automatically keeps already-visible items in the same
                    // visual position when DiffUtil reports an insert above
                    // the current viewport (that's one of the real advantages
                    // of this migration, not just smoother scrolling).
                    adapter.refreshWithoutScroll();
                    isLoadingMore = false;
                    if (progressLoadMore != null) progressLoadMore.setVisibility(View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    isLoadingMore = false;
                    if (progressLoadMore != null) progressLoadMore.setVisibility(View.GONE);
                }
            });
    }

    /**
     * Rebuilds messageList from a snapshot. Shared by the live listener and the
     * pagination loader. When newPeerMsgIdsOut/myLatestOut are non-null the caller
     * wants new-message tracking (live path); pagination passes null to skip it.
     */
    private void _rebuildMessageList(DataSnapshot snap,
                                     List<String> newPeerMsgIdsOut,
                                     String[] myLatestOut) {
        List<HashMap<String, Object>> tempMessages = new ArrayList<>();
        for (HashMap<String, Object> msg : messageList) {
            String mId = (String) msg.get("msgId");
            if (mId != null && mId.startsWith("temp_")) tempMessages.add(msg);
        }
        messageList.clear();

        for (DataSnapshot child : snap.getChildren()) {
            Boolean deletedForMe = child.child("deleted_for_" + myUid).getValue(Boolean.class);
            if (Boolean.TRUE.equals(deletedForMe)) continue;
            Boolean deletedForAll = child.child("is_deleted_for_all").getValue(Boolean.class);
            if (Boolean.TRUE.equals(deletedForAll)) continue;

            String mId    = child.getKey();
            String sUid   = child.child("sender_uid").getValue(String.class);
            String status = child.child("status").getValue(String.class);

            if (peerUid != null && peerUid.equals(sUid) && !"read".equals(status)
                    && getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
                        .getBoolean(PrivacySecurityActivity.KEY_READ_RCPT, true)) {
                HashMap<String, Object> upd = new HashMap<>();
                upd.put("status",  "read");
                upd.put("read_at", ServerValue.TIMESTAMP);
                child.getRef().updateChildren(upd);
                status = "read";
            }

            HashMap<String, Object> msg = new HashMap<>();
            msg.put("msgId",              mId);
            msg.put("sender_uid",         sUid);
            msg.put("text",               child.child("text").getValue(String.class));
            msg.put("image_url",          child.child("image_url").getValue(String.class));
            msg.put("voice_url",          child.child("voice_url").getValue(String.class));
            msg.put("duration",           child.child("duration").getValue(Long.class));
            msg.put("caption",            child.child("caption").getValue(String.class));
            msg.put("type",               child.child("type").getValue(String.class));
            msg.put("timestamp",          child.child("timestamp").getValue(Long.class));
            msg.put("is_edited",          child.child("is_edited").getValue(Boolean.class));
            msg.put("is_pinned",          child.child("is_pinned").getValue(Boolean.class));
            msg.put("is_forwarded",       child.child("is_forwarded").getValue(Boolean.class));
            msg.put("is_deleted_for_all", child.child("is_deleted_for_all").getValue(Boolean.class));
            msg.put("reply_to_id",        child.child("reply_to_id").getValue(String.class));
            msg.put("reply_sender",       child.child("reply_sender").getValue(String.class));
            msg.put("reply_preview",      child.child("reply_preview").getValue(String.class));
            msg.put("status",             status);
            msg.put("link_preview_url",   child.child("link_preview_url").getValue(String.class));
            msg.put("link_preview_title", child.child("link_preview_title").getValue(String.class));
            msg.put("link_preview_desc",  child.child("link_preview_desc").getValue(String.class));
            msg.put("link_preview_image", child.child("link_preview_image").getValue(String.class));
            msg.put("link_preview_site",  child.child("link_preview_site").getValue(String.class));
            {   // end-to-end encryption: replace the ciphertext envelope with the decrypted content
                HashMap<String, Object> raw = new HashMap<>();
                raw.put("ct",         child.child("ct").getValue(String.class));
                raw.put("x_ik",       child.child("x_ik").getValue(String.class));
                raw.put("x_ek",       child.child("x_ek").getValue(String.class));
                raw.put("x_spk",      child.child("x_spk").getValue(Long.class));
                raw.put("sender_uid", sUid);
                raw.put("timestamp",  child.child("timestamp").getValue(Long.class));
                com.silu.minigram.e2ee.E2ee.get(this, myUid).decryptInto(chatId, peerUid, mId, raw, msg);
                com.silu.minigram.e2ee.E2eeMedia.attach(this, msg,
                    () -> { if (!isFinishing() && !isDestroyed() && adapter != null) adapter.refreshWithoutScroll(); });
            }
            _applyTranslation(msg, mId, sUid, false);

            // Reactions: { uid: emoji } → count per emoji + whether I reacted
            HashMap<String, Integer> reactionCounts = new HashMap<>();
            String myReaction = null;
            DataSnapshot rSnap = child.child("reactions");
            for (DataSnapshot r : rSnap.getChildren()) {
                String emoji = r.getValue(String.class);
                if (emoji == null) continue;
                Integer cur = reactionCounts.get(emoji);
                reactionCounts.put(emoji, cur == null ? 1 : cur + 1);
                if (myUid != null && myUid.equals(r.getKey())) myReaction = emoji;
            }
            msg.put("reaction_counts", reactionCounts);
            msg.put("my_reaction",     myReaction);
            messageList.add(msg);

            if (mId != null && !knownMsgIds.contains(mId)) {
                if (!isFirstLoad && newPeerMsgIdsOut != null) {
                    if (peerUid != null && peerUid.equals(sUid)) {
                        newPeerMsgIdsOut.add(mId);
                        if (firstNewMsgId == null) firstNewMsgId = mId;
                    } else if (myUid != null && myUid.equals(sUid) && myLatestOut != null) {
                        myLatestOut[0] = mId;
                    }
                }
                knownMsgIds.add(mId);
            }
        }
        messageList.addAll(tempMessages);
        _resetMyUnread();

        // Mirror the confirmed set into the local cache (temp_ rows are skipped
        // inside cacheMessages) so the next open is instant + offline-capable.
        if (msgCache != null && chatId != null) {
            msgCache.cacheMessages(chatId, messageList);
        }
    }

    private void _listenMessages() {
        // Safety net: whatever happens (denied read, offline with nothing
        // cached, a callback that simply never arrives), the spinner must
        // not be able to sit there forever the way it did on new chats.
        // Whichever comes first — real data, onCancelled, or this — wins;
        // the others become no-ops since they all just hide the same view.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (layoutInitialLoading != null
                    && layoutInitialLoading.getVisibility() == View.VISIBLE) {
                Log.d("MinigramScroll", "initial load timeout — hiding spinner");
                isFirstLoad = false;
                layoutInitialLoading.setVisibility(View.GONE);
            }
        }, 8000);

        messagesListener = messagesRef.orderByChild("timestamp").limitToLast(messagesPageSize)
            .addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String>  newPeerMsgIds  = new ArrayList<>();
                    String[]      myLatestHolder = new String[1];

                    _rebuildMessageList(snap, newPeerMsgIds, myLatestHolder);
                    String myLatestMsgId = myLatestHolder[0];

                    if (isFirstLoad) {
                        isFirstLoad = false;
                        isAtBottom  = true;
                        if (layoutInitialLoading != null) layoutInitialLoading.setVisibility(View.GONE);
                        adapter.refreshWithoutScroll();
                        if (pendingScrollMsgId != null) {
                            final String target = pendingScrollMsgId;
                            pendingScrollMsgId = null;
                            listview1.post(() -> scrollToMessage(target));
                        } else {
                            listview1.post(() -> listview1.scrollToPosition(adapter.getItemCount() - 1));
                        }
                    } else {
                        adapter.refreshWithoutScroll();
                        // DIAGNOSTIC: har naye snapshot par exact branch dikhega.
                        Log.d("MinigramScroll", "onDataChange: newPeerMsgIds=" + newPeerMsgIds.size()
                                + " myLatestMsgId=" + myLatestMsgId + " isAtBottom=" + isAtBottom);
                        if (myLatestMsgId != null) {
                            // I just sent something — always jump to it.
                            // Checked BEFORE the peer branch on purpose: if a
                            // peer message lands in the same snapshot as my
                            // send, the old ordering took the peer branch and
                            // my own message could end up behind a "new
                            // messages" bubble instead of being scrolled to.
                            Log.d("MinigramScroll", "-> my own msg confirmed: force scroll bottom");
                            firstNewMsgId = null;
                            newMsgCount   = 0;
                            _hideBubble();
                            isAtBottom = true;
                            listview1.post(() -> listview1.scrollToPosition(adapter.getItemCount() - 1));
                        } else if (!newPeerMsgIds.isEmpty()) {
                            if (isAtBottom) {
                                Log.d("MinigramScroll", "-> peer msg, at bottom: auto-scroll");
                                firstNewMsgId = null;
                                newMsgCount   = 0;
                                listview1.post(() -> listview1.scrollToPosition(adapter.getItemCount() - 1));
                            } else {
                                Log.d("MinigramScroll", "-> peer msg, NOT at bottom: showBubble()");
                                newMsgCount += newPeerMsgIds.size();
                                _showBubble();
                            }
                        }
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    // BUG FIXED: this was empty, which is why opening a
                    // BRAND-NEW chat sat on "Loading messages…" forever.
                    // chats/$chatId's .read rule requires
                    // user_chats/{myUid}/{chatId} to exist — and for a chat
                    // that has never had a message, it doesn't yet (it only
                    // gets written on the first send). So Firebase correctly
                    // DENIES the read and calls onCancelled instead of
                    // onDataChange... and nothing here ever hid the spinner.
                    //
                    // The rule itself is right — it's what stops anyone
                    // reading a conversation they're not part of — so the
                    // fix belongs here: a denied read on a chat with no
                    // messages just means "nothing to show yet". Drop the
                    // spinner so the person can type the first message.
                    Log.d("MinigramScroll", "messages read cancelled: " + e.getMessage());
                    if (isFinishing() || isDestroyed()) return;
                    isFirstLoad = false;
                    if (layoutInitialLoading != null) layoutInitialLoading.setVisibility(View.GONE);
                }
            });
    }

    private void _listenPeerStatus() {
        if (peerUid == null || chatId == null) return;
        
        // 1. Listen for Online / Offline Status
        statusListener = database.getReference("users").child(peerUid).child("status")
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isPeerTyping) _updateStatusUI(snap.getValue(String.class));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        // 1b. Blue tick badge — lightweight, single boolean field
        database.getReference("users").child(peerUid).child("is_pro")
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    ImageView badge = findViewById(R.id.img_verified_badge);
                    if (badge != null)
                        badge.setVisibility(Boolean.TRUE.equals(snap.getValue(Boolean.class))
                            ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        // 2. Listen for Typing status (Read from user_chats node to bypass root permission errors)
        typingListener = database.getReference("user_chats").child(myUid).child(chatId).child("is_typing")
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Boolean typing = snap.getValue(Boolean.class);
                    isPeerTyping = Boolean.TRUE.equals(typing);
                    
                    if (isPeerTyping) {
                        if (txt_status != null) {
                            txt_status.setText("typing...");
                            txt_status.setTextColor(Ink.c("#4CAF50")); 
                        }
                    } else {
                        database.getReference("users").child(peerUid).child("status")
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot s) {
                                    _updateStatusUI(s.getValue(String.class));
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ----------------------------------------------------------------- auto-translate (on-device, after decrypt)
    private final java.util.Set<String> hinglishRequested = new java.util.HashSet<>();
    private TextView translateChip;

    /** Applies a cached translation to an incoming text message, or starts one in the background. */
    private void _applyTranslation(HashMap<String, Object> msg, String mId, String sUid, boolean forceHinglish) {
        if (peerUid == null || !peerUid.equals(sUid) || msg.containsKey("original_text")) return;
        if (!"text".equals(msg.get("type")) || msg.containsKey("e2ee_unreadable")) return;
        if (!com.silu.minigram.translate.LangPrefs.auto(this) || !com.silu.minigram.translate.LangPrefs.chatAuto(this, chatId)) return;
        final String text = msg.get("text") instanceof String ? (String) msg.get("text") : "";
        if (text.trim().length() < 2 || text.startsWith("\uD83D\uDD12") || text.startsWith("\u26A0")) return;
        final String mine = com.silu.minigram.translate.LangPrefs.myLang(this);
        final String key = mId + ":" + text.hashCode() + ":" + mine;
        final com.silu.minigram.translate.TranslationManager tm = com.silu.minigram.translate.TranslationManager.get(this);
        com.silu.minigram.translate.TranslationManager.Outcome o = tm.cached(key);
        if (o == null || (forceHinglish && o.translated == null && o.approximate)) {
            final boolean manual = forceHinglish || hinglishRequested.contains(mId);
            if (o != null) tm.clearCache();
            tm.translate(key, text, mine, manual, r -> {
                if (isFinishing() || isDestroyed() || adapter == null) return;
                for (HashMap<String, Object> m : messageList) {
                    if (mId.equals(m.get("msgId"))) { _applyOutcome(m, text, r); break; }
                }
                adapter.refreshWithoutScroll();
            });
            return;
        }
        _applyOutcome(msg, text, o);
    }

    private void _applyOutcome(HashMap<String, Object> msg, String original, com.silu.minigram.translate.TranslationManager.Outcome o) {
        if (msg.containsKey("original_text")) return;
        if (o.translated != null && !o.translated.isEmpty() && !o.translated.equals(original)) {
            msg.put("original_text", original);
            msg.put("text", o.translated);
            msg.put("translated_from", o.sourceLang);
            msg.put("translation_approx", o.approximate);
            _updateTranslateButton();
            if (o.hinglish) _maybeAskHinglishConsent();
        } else if (o.needsPack) {
            msg.put("translate_pack", o.packLang);
        }
    }

    private boolean hinglishAsked;
    /** One-time opt-in: online transliteration is far more accurate for Hinglish but sends the text to Google. */
    private void _maybeAskHinglishConsent() {
        if (hinglishAsked || com.silu.minigram.translate.LangPrefs.hinglishOnline(this) != 0 || isFinishing()) return;
        hinglishAsked = true;
        new android.app.AlertDialog.Builder(this)
            .setTitle("Better Hinglish translation?")
            .setMessage("Hinglish (Hindi in English letters) is translated much more accurately using Google Input Tools. "
                + "This sends only the message text to Google to convert it to Hindi script; it stays off otherwise "
                + "and everything else is translated on your phone.\n\nYou can change this anytime in the translate options.")
            .setPositiveButton("Turn on", (d, w) -> {
                com.silu.minigram.translate.LangPrefs.setHinglishOnline(this, true);
                com.silu.minigram.translate.TranslationManager.get(this).clearCache();
            })
            .setNegativeButton("Not now", (d, w) -> com.silu.minigram.translate.LangPrefs.setHinglishOnline(this, false))
            .show();
    }

    private TextView translateBtn;

    /** Header translate button: accent when auto-translate is on for this chat; tap opens the translate sheet. */
    private void _setupTranslateButton() {
        try {
            if (translateBtn != null || toolbar == null || more_options == null || more_options.getParent() != toolbar) return;
            translateBtn = new TextView(this);
            translateBtn.setText("translate");
            translateBtn.setTextSize(18);
            translateBtn.setGravity(android.view.Gravity.CENTER);
            translateBtn.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.material_icons));
            translateBtn.setBackgroundResource(R.drawable.btn_round_outline);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.setMarginEnd(dp(6));
            translateBtn.setLayoutParams(lp);
            translateBtn.setOnClickListener(v -> _showTranslateSheet());
            toolbar.addView(translateBtn, toolbar.indexOfChild(more_options));
            _updateTranslateButton();
        } catch (Throwable ignored) { }
    }

    private void _updateTranslateButton() {
        if (translateBtn == null) return;
        boolean on = com.silu.minigram.translate.LangPrefs.auto(this) && com.silu.minigram.translate.LangPrefs.chatAuto(this, chatId);
        translateBtn.setTextColor(Ink.res(on ? R.color.ink_accent : R.color.ink_text_2));
        translateBtn.setContentDescription(on ? "Translation on. Open translate options" : "Translation off. Open translate options");
    }

    private void _showTranslateSheet() {
        final com.google.android.material.bottomsheet.BottomSheetDialog d = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(24));
        TextView title = new TextView(this);
        title.setText("Translate messages");
        title.setTextAppearance(R.style.TextAppearance_Ink_Title);
        box.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Translation runs on your phone. Nothing is sent to a server.");
        sub.setTextColor(Ink.res(R.color.ink_text_2));
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, dp(12));
        box.addView(sub);

        final com.google.android.material.materialswitch.MaterialSwitch swChat = new com.google.android.material.materialswitch.MaterialSwitch(this);
        swChat.setText("Auto-translate this chat");
        swChat.setMinHeight(dp(48));
        swChat.setChecked(com.silu.minigram.translate.LangPrefs.chatAuto(this, chatId));
        box.addView(swChat);

        final com.google.android.material.materialswitch.MaterialSwitch swOrig = new com.google.android.material.materialswitch.MaterialSwitch(this);
        swOrig.setText("Always show original text");
        swOrig.setMinHeight(dp(48));
        swOrig.setChecked(com.silu.minigram.translate.LangPrefs.showOriginal(this));
        box.addView(swOrig);

        final com.google.android.material.materialswitch.MaterialSwitch swHing = new com.google.android.material.materialswitch.MaterialSwitch(this);
        swHing.setText("Better Hinglish accuracy (online)");
        swHing.setMinHeight(dp(48));
        swHing.setChecked(com.silu.minigram.translate.LangPrefs.hinglishOnline(this) == 1);
        swHing.setOnCheckedChangeListener((b, on) -> {
            com.silu.minigram.translate.LangPrefs.setHinglishOnline(this, on);
            com.silu.minigram.translate.TranslationManager.get(this).clearCache();
        });
        box.addView(swHing);

        TextView lang = new TextView(this);
        lang.setText("Translate to: " + com.silu.minigram.translate.TranslationManager.displayName(
            com.silu.minigram.translate.LangPrefs.myLang(this), java.util.Locale.getDefault()) + "  \u203A");
        lang.setMinHeight(dp(48));
        lang.setGravity(android.view.Gravity.CENTER_VERTICAL);
        lang.setTextColor(Ink.res(R.color.ink_accent));
        lang.setOnClickListener(v -> { d.dismiss(); startActivity(new Intent(this, LanguageSettingsActivity.class)); });
        box.addView(lang);

        swChat.setOnCheckedChangeListener((b, on) -> {
            com.silu.minigram.translate.LangPrefs.setChatAuto(this, chatId, on);
            _updateTranslateButton();
            if (on) {
                for (HashMap<String, Object> m : new ArrayList<>(messageList))
                    _applyTranslation(m, String.valueOf(m.get("msgId")), (String) m.get("sender_uid"), false);
            } else {
                for (HashMap<String, Object> m : messageList) {
                    Object o = m.get("original_text");
                    if (o != null) { m.put("text", o); m.remove("original_text"); m.remove("translated_from"); m.remove("translation_approx"); }
                }
            }
            adapter.refreshWithoutScroll();
        });
        swOrig.setOnCheckedChangeListener((b, on) -> {
            com.silu.minigram.translate.LangPrefs.set(this, com.silu.minigram.translate.LangPrefs.SHOW_ORIGINAL, on);
            adapter.refreshWithoutScroll();
        });
        d.setContentView(box);
        d.show();
    }

    private String lastStatus = "offline";

    /** Header sub-line shows a lock once the conversation is end-to-end encrypted. */
    private void _showEncryptionState() {
        _updateStatusUI(lastStatus);
    }

    private void _bindTranslateListener() {
        adapter.setTranslateListener(new ChatMessageRecyclerAdapter.TranslateListener() {
            @Override public void onTranslateHinglish(String msgId) {
                hinglishRequested.add(msgId);
                for (HashMap<String, Object> m : messageList) {
                    if (msgId.equals(m.get("msgId"))) {
                        m.remove("hinglish_offer");
                        _applyTranslation(m, msgId, peerUid, true);
                        break;
                    }
                }
            }
            @Override public void onNeedPack(String lang) {
                startActivity(new Intent(ChatActivity.this, LanguageSettingsActivity.class));
            }
        });
    }

    private void _initEncryption() {
        final com.silu.minigram.e2ee.E2ee e2 = com.silu.minigram.e2ee.E2ee.get(this, myUid);
        e2.ensureIdentity();
        useE2ee = e2.isEncrypted(chatId);
        if (peerUid == null || peerUid.isEmpty()) return;
        e2.refreshPeer(chatId, peerUid, ok -> {
            if (isFinishing() || isDestroyed()) return;
            useE2ee = ok || e2.isEncrypted(chatId);
            _showEncryptionState();
            if (e2.hasKeyChange(chatId)) _warnKeyChange();
        });
    }

    private boolean keyWarningShown = false;
    private void _warnKeyChange() {
        if (keyWarningShown) return;
        keyWarningShown = true;
        new AlertDialog.Builder(this)
            .setTitle("Security code changed")
            .setMessage((peerName != null ? peerName : "Your contact") + " may have reinstalled Minigram or switched phones. "
                + "New messages stay hidden until you review the new code. Verify it with them in person or over a call if you can.")
            .setPositiveButton("Review", (d, w) -> _openVerify())
            .setNegativeButton("Later", null).show();
    }

    private void _openVerify() {
        Intent i = new Intent(this, VerifyEncryptionActivity.class);
        i.putExtra("chat_id", chatId);
        i.putExtra("peer_uid", peerUid);
        i.putExtra("peer_name", peerName);
        startActivity(i);
    }

    private void _updateStatusUI(String status) {
        if (txt_status == null) return;
        lastStatus = status == null ? "offline" : status;
        boolean online = "online".equals(status);
        String lock = useE2ee ? " \u00b7 \uD83D\uDD12 E2EE" : "";
        if (online) {
            txt_status.setText("online" + lock);
            txt_status.setTextColor(Ink.c("#4CAF50"));
            return;
        }
        txt_status.setText("offline" + lock);
        txt_status.setTextColor(Ink.c("#9E9E9E"));
        if (peerUid == null || database == null) return;
        database.getReference("users").child(peerUid).child("last_active_ts")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Long ts = snap.getValue(Long.class);
                    if (ts == null || txt_status == null) return;
                    if (txt_status.getText().toString().startsWith("online")) return;
                    txt_status.setText("last seen " + _formatLastSeen(ts) + (useE2ee ? " \u00b7 \uD83D\uDD12 E2EE" : ""));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private String _formatLastSeen(long ts) {
        long diffMs = System.currentTimeMillis() - ts;
        long mins = diffMs / 60000;
        if (mins < 1)   return "just now";
        if (mins < 60)  return mins + "m ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days == 1)  return "yesterday";
        if (days < 7)   return days + "d ago";
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(ts));
    }

    private void _resetMyUnread() {
        if (myUid == null || chatId == null || userChatsRef == null) return;
        HashMap<String, Object> upd = new HashMap<>();
        upd.put("unread",       0);
        upd.put("last_read_ts", ServerValue.TIMESTAMP);
        userChatsRef.child(myUid).child(chatId).updateChildren(upd);
    }

    private void _markMessagesRead() {
        _resetMyUnread();
        if (peerUid == null || messagesRef == null) return;
        boolean readReceiptsOn = getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
            .getBoolean(PrivacySecurityActivity.KEY_READ_RCPT, true);
        if (!readReceiptsOn) return;
        messagesRef.orderByChild("sender_uid").equalTo(peerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        String st = child.child("status").getValue(String.class);
                        if (!"read".equals(st)) {
                            HashMap<String, Object> upd = new HashMap<>();
                            upd.put("status",  "read");
                            upd.put("read_at", ServerValue.TIMESTAMP);
                            child.getRef().updateChildren(upd);
                        }
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── VOICE MESSAGE RECORDING ─────────────────────────────────────────────

    // ----------------------------------------------------------------- voice notes (live recorder + pipeline)
    private com.silu.minigram.voice.LiveRecorder liveRecorder;
    private File rawVoiceFile;
    private com.silu.minigram.voice.WaveformView voiceWave;
    private TextView voiceTimer;

    private void _startVoiceRecording() {
        if (isRecording) return;

        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }

        try {
            File outDir = new File(getCacheDir(), "voice_notes");
            if (!outDir.exists()) outDir.mkdirs();
            rawVoiceFile = new File(outDir, "raw_" + System.currentTimeMillis() + ".pcm");
            voiceFilePath = rawVoiceFile.getAbsolutePath();

            _showRecordingBar();
            liveRecorder = new com.silu.minigram.voice.LiveRecorder(rawVoiceFile, new com.silu.minigram.voice.LiveRecorder.Listener() {
                @Override public void onLevel(float level) { if (voiceWave != null) voiceWave.push(level); }
                @Override public void onError(Exception e) {
                    Log.e("VoiceRecord", "capture failed", e);
                    if (isRecording) _stopVoiceRecording(true);
                    Toast.makeText(ChatActivity.this, "Recording failed", Toast.LENGTH_SHORT).show();
                }
            });
            liveRecorder.start();

            isRecording = true;
            recordingCancelled = false;
            recordingStartTime = System.currentTimeMillis();


            recordingTimerRunnable = new Runnable() {
                @Override public void run() {
                    if (!isRecording) return;
                    long secs = liveRecorder != null ? liveRecorder.samples() / com.silu.minigram.voice.LiveRecorder.SAMPLE_RATE : 0;
                    if (voiceTimer != null) voiceTimer.setText(String.format(java.util.Locale.US, "%d:%02d", secs / 60, secs % 60));
                    recordingTimerHandler.postDelayed(this, 250);
                }
            };
            recordingTimerHandler.post(recordingTimerRunnable);

        } catch (Exception e) {
            Log.e("VoiceRecord", "Failed to start recording", e);
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show();
            _hideVoiceCard();
            _resetRecordingUI();
        }
    }

    // ---- WhatsApp-style gesture: hold to record, release to send, slide left to cancel, slide up to lock ----
    private boolean micLocked = false, micHolding = false, micTouchStartedLocked = false;
    private float micDownX, micDownY;
    private View lockHint;

    private boolean _onMicTouch(View v, android.view.MotionEvent e) {
        boolean textMode = !edit_msg.getText().toString().trim().isEmpty();
        if (textMode && !isRecording) return false;                 // normal click sends the text
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                micTouchStartedLocked = micLocked;
                if (micLocked) return true;                         // tap on "send" while locked: handled on UP
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                    return true;
                }
                micDownX = e.getRawX(); micDownY = e.getRawY();
                v.getParent().requestDisallowInterceptTouchEvent(true);
                for (android.view.ViewParent p = v.getParent(); p instanceof android.view.ViewGroup; p = p.getParent()) {
                    ((android.view.ViewGroup) p).setClipChildren(false);
                    ((android.view.ViewGroup) p).setClipToPadding(false);
                }
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                _startVoiceRecording();
                if (isRecording) { micHolding = true; v.animate().scaleX(1.6f).scaleY(1.6f).setDuration(120).start(); _showLockHint(v); }
                return true;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!micHolding || micLocked) return true;
                float dx = e.getRawX() - micDownX, dy = e.getRawY() - micDownY;
                if (dx > 0) dx = 0;
                View slide = findViewById(R.id.layout_slide_to_cancel);
                if (slide != null) { slide.setTranslationX(dx * 0.7f); slide.setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / dp(160))); }
                if (lockHint != null) lockHint.setTranslationY(Math.max(-dp(70), Math.min(0, dy * 0.5f)));
                if (dx < -dp(110)) { micHolding = false; _endMicGesture(v); v.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT); _stopVoiceRecording(true); }
                else if (dy < -dp(90)) { micLocked = true; micHolding = false; _endMicGesture(v); v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); _enterLockedUi(); }
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (micTouchStartedLocked && micLocked) {            // tapped send while locked
                    micLocked = false;
                    _stopVoiceRecording(e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL);
                    return true;
                }
                if (micHolding) {
                    micHolding = false;
                    _endMicGesture(v);
                    long ms = System.currentTimeMillis() - recordingStartTime;
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) _stopVoiceRecording(true);
                    else if (ms < 700) { _stopVoiceRecording(true); Toast.makeText(this, "Hold the mic to record, release to send", Toast.LENGTH_SHORT).show(); }
                    else _stopVoiceRecording(false);
                }
                return true;
        }
        return false;
    }

    private void _endMicGesture(View v) {
        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        if (lockHint != null) { lockHint.setVisibility(View.GONE); lockHint.setTranslationY(0); }
        View slide = findViewById(R.id.layout_slide_to_cancel);
        if (slide != null) { slide.setTranslationX(0); slide.setAlpha(1f); }
    }

    private void _showLockHint(View anchor) {
        if (lockHint == null) {
            TextView t = new TextView(this);
            t.setText("lock\nkeyboard_arrow_up");
            t.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.material_icons));
            t.setTextSize(20);
            t.setGravity(android.view.Gravity.CENTER);
            t.setTextColor(Ink.res(R.color.ink_text));
            t.setBackground(Appearance.bubble(this, false));
            t.setPadding(dp(10), dp(12), dp(10), dp(8));
            t.setElevation(dp(6));
            t.setContentDescription("Slide up to lock recording");
            t.setTransformationMethod(null);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dp(48), dp(104), android.view.Gravity.BOTTOM | android.view.Gravity.END);
            lp.rightMargin = dp(14); lp.bottomMargin = dp(112);
            ((android.widget.FrameLayout) findViewById(android.R.id.content)).addView(t, lp);
            lockHint = t;
        }
        lockHint.setTranslationY(0);
        lockHint.setVisibility(View.VISIBLE);
    }

    /** After sliding up: the bar becomes [trash] dot timer ~waveform~ [pause]; the big button becomes "send". */
    private void _enterLockedUi() {
        View slide = findViewById(R.id.layout_slide_to_cancel);
        if (slide != null) slide.setVisibility(View.GONE);
        View trash = findViewById(R.id.btn_rec_trash), pause = findViewById(R.id.btn_rec_pause), wave = findViewById(R.id.wave_inline);
        if (trash != null) trash.setVisibility(View.VISIBLE);
        if (pause != null) { pause.setVisibility(View.VISIBLE); ((TextView) pause).setText("pause"); }
        if (wave != null) wave.setVisibility(View.VISIBLE);
        if (btn_send_icon != null) { btn_send_icon.setText("send"); btn_send.setContentDescription("Send voice message"); }
    }

    private void _togglePauseRecording() {
        if (liveRecorder == null) return;
        boolean p = !liveRecorder.isPaused();
        liveRecorder.setPaused(p);
        TextView pause = findViewById(R.id.btn_rec_pause);
        if (pause != null) { pause.setText(p ? "mic" : "pause"); pause.setContentDescription(p ? "Resume recording" : "Pause recording"); }
        if (p) _stopPulseAnimation(); else _startPulseAnimation();
    }

    private void _showRecordingBar() {
        voiceWave = findViewById(R.id.wave_inline);
        voiceTimer = txt_recording_timer;
        if (voiceWave != null) voiceWave.reset();
        if (voiceTimer != null) voiceTimer.setText("0:00");
        micLocked = false;
        if (btn_attach_icon != null) btn_attach_icon.setVisibility(View.GONE);
        if (linear18 != null) linear18.setVisibility(View.GONE);
        if (layout_recording_bar != null) layout_recording_bar.setVisibility(View.VISIBLE);
        View slide = findViewById(R.id.layout_slide_to_cancel);
        if (slide != null) { slide.setVisibility(View.VISIBLE); slide.setTranslationX(0); slide.setAlpha(1f); }
        for (int id : new int[]{R.id.btn_rec_trash, R.id.btn_rec_pause, R.id.wave_inline}) { View w = findViewById(id); if (w != null) w.setVisibility(View.GONE); }
        _startPulseAnimation();
    }

    private void _hideVoiceCard() {
        micLocked = false; micHolding = false;
        if (lockHint != null) lockHint.setVisibility(View.GONE);
        if (btn_send != null) { btn_send.setScaleX(1f); btn_send.setScaleY(1f); }
    }

    private void _stopVoiceRecording(boolean cancelled) {
        if (!isRecording) return;
        recordingCancelled = cancelled;
        isRecording = false;
        long samples = 0;
        if (liveRecorder != null) { samples = liveRecorder.stop(); }
        final int platformFx = liveRecorder != null && (liveRecorder.nsActive || liveRecorder.agcActive || liveRecorder.aecActive) ? 1 : 0;
        liveRecorder = null;
        final long durationSec = samples / com.silu.minigram.voice.LiveRecorder.SAMPLE_RATE;

        _hideVoiceCard();
        _resetRecordingUI();

        if (recordingCancelled || durationSec < 1) {
            if (rawVoiceFile != null) rawVoiceFile.delete();
            rawVoiceFile = null; voiceFilePath = null;
            if (cancelled) Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show();
            return;
        }

        final File raw = rawVoiceFile;
        rawVoiceFile = null; voiceFilePath = null;

        // Optimistic placeholder - shows immediately, like images do
        final String tempId = "temp_" + System.currentTimeMillis();
        HashMap<String, Object> tempMsg = new HashMap<>();
        tempMsg.put("msgId",              tempId);
        tempMsg.put("sender_uid",         myUid);
        tempMsg.put("type",               "voice");
        tempMsg.put("voice_url",          "");
        tempMsg.put("duration",           (int) durationSec);
        tempMsg.put("caption",            "");
        tempMsg.put("text",               "");
        tempMsg.put("timestamp",          System.currentTimeMillis());
        tempMsg.put("is_edited",          false);
        tempMsg.put("is_pinned",          false);
        tempMsg.put("is_forwarded",       false);
        tempMsg.put("is_deleted_for_all", false);
        tempMsg.put("status",             "pending");
        tempMsg.put("upload_state",       "uploading");
        messageList.add(tempMsg);
        adapter.refreshWithoutScroll();
        listview1.post(() -> listview1.scrollToPosition(adapter.getItemCount() - 1));
        isAtBottom = true;

        // Denoise/level/encode off the UI thread, then upload (encrypted on-device in E2EE chats)
        com.silu.minigram.voice.VoicePipeline.processAsync(this, raw, platformFx, new com.silu.minigram.voice.VoicePipeline.Callback() {
            @Override public void onDone(com.silu.minigram.voice.VoiceEncoder.Result r, int dur, String stages) {
                Log.d("VoiceRecord", "pipeline: " + stages);
                if (isFinishing() || isDestroyed()) return;
                pendingRetryVoiceFiles.put(tempId, r.file);
                _uploadVoice(tempId, r.file, dur > 0 ? dur : (int) durationSec);
            }
            @Override public void onError(Exception e) {
                Log.e("VoiceRecord", "pipeline failed", e);
                if (isFinishing() || isDestroyed()) return;
                _setTempMsgState(tempId, "failed");
                Toast.makeText(ChatActivity.this, "Could not process the recording", Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Uploads a recorded voice note (encrypted on-device when the chat is end-to-end encrypted) then sends it. */
    private void _uploadVoice(final String tempId, final File audioFile, final int durationSec) {
        if (useE2ee) {
            VoiceUploadHelper.uploadVoiceEncrypted(audioFile, new VoiceUploadHelper.EncCallback() {
                @Override public void onSuccess(String fileUrl, String keyB64) {
                    runOnUiThread(() -> {
                        retryQueue.onSuccess(tempId);
                        _removeTempMsg(tempId);
                        pendingRetryVoiceFiles.remove(tempId);
                        pendingMediaKey = keyB64;
                        _sendMessage("voice", "", fileUrl, "", false, durationSec);
                        audioFile.delete();
                    });
                }
                @Override public void onError(String errorMessage) {
                    runOnUiThread(() -> {
                        _setTempMsgState(tempId, "failed");
                        Toast.makeText(ChatActivity.this, "Voice upload failed: " + errorMessage, Toast.LENGTH_LONG).show();
                    });
                }
            });
            return;
        }
        VoiceUploadHelper.uploadVoice(audioFile, new VoiceUploadHelper.UploadCallback() {
            @Override public void onSuccess(String fileUrl) {
                runOnUiThread(() -> {
                    retryQueue.onSuccess(tempId);
                    _removeTempMsg(tempId);
                    pendingRetryVoiceFiles.remove(tempId);
                    _sendMessage("voice", "", fileUrl, "", false, durationSec);
                    audioFile.delete();
                });
            }
            @Override public void onError(String errorMessage) {
                runOnUiThread(() -> {
                    _setTempMsgState(tempId, "failed");
                    Toast.makeText(ChatActivity.this, "Voice upload failed: " + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void _resetRecordingUI() {
        if (recordingTimerRunnable != null) recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        _stopPulseAnimation();
        if (layout_recording_bar != null) layout_recording_bar.setVisibility(View.GONE);
        if (btn_attach_icon != null) btn_attach_icon.setVisibility(View.VISIBLE);
        if (linear18 != null) linear18.setVisibility(View.VISIBLE);
        if (txt_recording_timer != null) txt_recording_timer.setText("0:00");
        if (layout_slide_to_cancel != null) {
            layout_slide_to_cancel.setTranslationX(0f);
            layout_slide_to_cancel.setAlpha(1f);
        }
        if (btn_send_icon != null) {
            btn_send_icon.setText(edit_msg.getText().toString().trim().isEmpty() ? "mic" : "send");
        }
    }

    private void _startPulseAnimation() {
        if (dot_recording == null) return;
        _stopPulseAnimation();
        dotPulseAnimator = android.animation.ObjectAnimator.ofFloat(dot_recording, "alpha", 1f, 0.25f);
        dotPulseAnimator.setDuration(600);
        dotPulseAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        dotPulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        dotPulseAnimator.start();
    }

    private void _stopPulseAnimation() {
        if (dotPulseAnimator != null) {
            dotPulseAnimator.cancel();
            dotPulseAnimator = null;
        }
        if (dot_recording != null) dot_recording.setAlpha(1f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone allowed. Hold the mic button to record.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Voice message ke liye microphone permission chahiye", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── Chat commands: /bold, /italic, /strike, /repeat ─────────────────────
    // Commands are resolved BEFORE sending — the receiver (and sender's own
    // bubble) only ever sees the final styled/repeated result, never the
    // raw "/command" text.
    private static final int MAX_REPEAT = 50; // spam-abuse guard

    private void _setupChatSearch() {
        if (btn_search_chat == null) return;

        btn_search_chat.setOnClickListener(v -> {
            if (search_bar != null) search_bar.setVisibility(View.VISIBLE);
            if (edit_search != null) {
                edit_search.requestFocus();
                edit_search.setText("");
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(edit_search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });

        if (btn_search_close != null) btn_search_close.setOnClickListener(v -> _closeSearch());

        if (edit_search != null) {
            edit_search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { _runSearch(s.toString().trim()); }
            });
        }

        if (btn_search_next != null) btn_search_next.setOnClickListener(v -> _navigateSearch(+1));
        if (btn_search_prev != null) btn_search_prev.setOnClickListener(v -> _navigateSearch(-1));
    }

    private void _runSearch(String query) {
        searchMatchPositions.clear();
        searchCurrentIndex = -1;

        if (query.isEmpty()) {
            if (txt_search_count != null) txt_search_count.setText("");
            if (adapter != null) adapter.setSearchQuery(null);
            return;
        }

        String lower = query.toLowerCase(Locale.getDefault());
        for (int i = 0; i < messageList.size(); i++) {
            HashMap<String, Object> m = messageList.get(i);
            String text    = (String) m.get("text");
            String caption = (String) m.get("caption");
            String hay = ((text != null ? text : "") + " " + (caption != null ? caption : ""))
                .toLowerCase(Locale.getDefault());
            if (hay.contains(lower)) searchMatchPositions.add(i);
        }

        if (adapter != null) adapter.setSearchQuery(query);

        if (searchMatchPositions.isEmpty()) {
            if (txt_search_count != null) txt_search_count.setText("0/0");
        } else {
            // Start at the most recent (bottom-most) match — closest to where the user is
            searchCurrentIndex = searchMatchPositions.size() - 1;
            _jumpToCurrentMatch();
        }
    }

    private void _navigateSearch(int dir) {
        if (searchMatchPositions.isEmpty()) return;
        searchCurrentIndex += dir;
        if (searchCurrentIndex < 0) searchCurrentIndex = searchMatchPositions.size() - 1;
        if (searchCurrentIndex >= searchMatchPositions.size()) searchCurrentIndex = 0;
        _jumpToCurrentMatch();
    }

    private void _jumpToCurrentMatch() {
        if (searchCurrentIndex < 0 || searchCurrentIndex >= searchMatchPositions.size()) return;
        final int pos = searchMatchPositions.get(searchCurrentIndex);
        if (txt_search_count != null)
            txt_search_count.setText((searchCurrentIndex + 1) + "/" + searchMatchPositions.size());
        if (adapter != null) adapter.setHighlightedPosition(pos);
        listview1.post(() -> listview1.scrollToPosition(pos));
    }

    private void _closeSearch() {
        if (search_bar != null) search_bar.setVisibility(View.GONE);
        if (edit_search != null) edit_search.setText("");
        searchMatchPositions.clear();
        searchCurrentIndex = -1;
        if (adapter != null) { adapter.setSearchQuery(null); adapter.setHighlightedPosition(-1); }
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && edit_search != null)
            imm.hideSoftInputFromWindow(edit_search.getWindowToken(), 0);
    }

    private void _processAndSendText(String raw) {
        String trimmed = raw.trim();

        java.util.regex.Matcher rm = java.util.regex.Pattern
            .compile("(?is)^/repeat\\s+(\\d+)\\s+(.+)$").matcher(trimmed);
        if (rm.matches()) {
            int count = Math.min(Math.max(Integer.parseInt(rm.group(1)), 1), MAX_REPEAT);
            String body = rm.group(2);
            for (int i = 0; i < count; i++) _sendMessage("text", body, "", "", false);
            return;
        }
        java.util.regex.Matcher bm = java.util.regex.Pattern
            .compile("(?is)^/bold\\s+(.+)$").matcher(trimmed);
        if (bm.matches()) { _sendMessage("text", "**" + bm.group(1) + "**", "", "", false); return; }

        java.util.regex.Matcher im = java.util.regex.Pattern
            .compile("(?is)^/italic\\s+(.+)$").matcher(trimmed);
        if (im.matches()) { _sendMessage("text", "_" + im.group(1) + "_", "", "", false); return; }

        java.util.regex.Matcher sm = java.util.regex.Pattern
            .compile("(?is)^/strike\\s+(.+)$").matcher(trimmed);
        if (sm.matches()) { _sendMessage("text", "~~" + sm.group(1) + "~~", "", "", false); return; }

        _sendMessage("text", trimmed, "", "", false);
    }

    /** Marker subclass so we can find-and-clear only OUR dim-span, not any other. */
    private static class CommandDimSpan extends ForegroundColorSpan {
        CommandDimSpan(int color) { super(color); }
    }

    private void _updateCommandDimming(Editable s) {
        CommandDimSpan[] old = s.getSpans(0, s.length(), CommandDimSpan.class);
        for (CommandDimSpan sp : old) s.removeSpan(sp);
        String str = s.toString();
        if (str.startsWith("/")) {
            int end = str.indexOf(' ');
            if (end == -1) end = str.length();
            if (end > 0) {
                s.setSpan(new CommandDimSpan(Ink.c("#9E9E9E")), 0, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private void _sendMessage(String type, String text, String imageUrl,
                               String caption, boolean isForwarded) {
        _sendMessage(type, text, imageUrl, caption, isForwarded, 0);
    }

    private void _sendMessage(String type, String text, String imageUrl,
                               String caption, boolean isForwarded, int durationSec) {
        String msgId = messagesRef.push().getKey();
        if (msgId == null) return;
        long ts = System.currentTimeMillis();

        HashMap<String, Object> msg = new HashMap<>();
        msg.put("sender_uid",         myUid);
        msg.put("type",               type);
        msg.put("text",               text);
        msg.put("image_url",          imageUrl);
        msg.put("caption",            caption);
        msg.put("timestamp",          ts);
        msg.put("is_edited",          false);
        msg.put("is_pinned",          false);
        msg.put("is_forwarded",       isForwarded);
        msg.put("is_deleted_for_all", false);
        msg.put("is_deleted_for_me",  false);
        msg.put("status",             "sent");
        if ("voice".equals(type)) {
            msg.put("voice_url", imageUrl); // reuse imageUrl param as the media URL
            msg.put("duration",  durationSec);
        }

        // If a link preview was already fetched while composing (and the
        // text still contains that exact URL), embed it directly into the
        // message — this is what lets the chat bubble render instantly
        // with zero fetching, for the sender AND everyone else who sees it.
        if ("text".equals(type) && composedPreview != null && composedPreviewUrl != null
                && text != null && text.contains(composedPreviewUrl)) {
            msg.put("link_preview_url",   composedPreview.sourceUrl);
            msg.put("link_preview_title", composedPreview.title);
            msg.put("link_preview_desc",  composedPreview.description);
            msg.put("link_preview_image", composedPreview.imageUrl);
            msg.put("link_preview_site",  composedPreview.siteName);
        }
        composedPreview = null;
        composedPreviewUrl = null;
        if (layoutComposeLinkPreview != null) layoutComposeLinkPreview.setVisibility(View.GONE);

        if (replyToId != null) {
            String senderLabel = "User";
            for (HashMap<String, Object> m : messageList) {
                if (replyToId.equals(m.get("msgId"))) {
                    String sUid = (String) m.get("sender_uid");
                    senderLabel = myUid.equals(sUid) ? "You" : (peerName != null ? peerName : "User");
                    break;
                }
            }
            msg.put("reply_to_id",   replyToId);
            msg.put("reply_preview", replyPreview);
            msg.put("reply_sender",  senderLabel);
        }

        // BUG FIXED: this used to call knownMsgIds.add(msgId) here, BEFORE
        // writing to Firebase. _rebuildMessageList() only reports a message
        // as "mine, just arrived" (myLatestOut) when its id is NOT already
        // in knownMsgIds — so pre-registering it meant the confirmed message
        // looked already-seen, myLatestMsgId stayed null, and the
        // "my own msg → scroll to bottom" branch in the listener never ran.
        // That's why sending a message didn't scroll to it.
        //
        // Removing the pre-add is safe: a message can never be mistaken for
        // an incoming peer message, because that branch matches on
        // sender_uid (peerUid.equals(sUid)), not on this set.
        String preview = "image".equals(type) ? "\uD83D\uDCF7 Photo"
                        : "voice".equals(type) ? "\uD83C\uDFA4 Voice message"
                        : "sticker".equals(type) ? Stickers.label(text)
                        : "file".equals(type) ? _filePreview(caption)
                        : text;
        _commitMessage(msgId, msg, preview, ts, ("voice".equals(type) || "file".equals(type)) ? "" : imageUrl);
        edit_msg.setText("");
        if (chatId != null)
            getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).edit().remove(chatId).apply();
        _clearReply();

        isCurrentlyTyping = false;
        if (stopTypingRunnable != null) typingHandler.removeCallbacks(stopTypingRunnable);
        database.getReference("user_chats").child(peerUid).child(chatId).child("is_typing").setValue(false);

        _hideBubble();
        isAtBottom = true;

    }

    /**
     * Single place where an outgoing chat message leaves the phone. In an end-to-end encrypted
     * chat the message is encrypted first; the server only ever stores ciphertext, a generic
     * list preview and a generic push text.
     */
    private void _commitMessage(final String msgId, final HashMap<String, Object> msg,
                                final String preview, final long ts, final String pushImageUrl) {
        final String type = String.valueOf(msg.get("type"));
        if (pendingMediaKey != null) { msg.put("e2ee_media_key", pendingMediaKey); pendingMediaKey = null; }
        final String plainText = String.valueOf(msg.get("text"));
        com.silu.minigram.e2ee.E2ee e2 = com.silu.minigram.e2ee.E2ee.get(this, myUid);
        e2.prepareOutgoing(chatId, peerUid, msgId, msg, new com.silu.minigram.e2ee.E2ee.OutCb() {
            @Override public void ready(java.util.Map<String, Object> toStore) {
                messagesRef.child(msgId).setValue(toStore);
                e2.setLocalPreview(chatId, preview, ts);
                useE2ee = true;
                _showEncryptionState();
                _updateUserChats(com.silu.minigram.e2ee.E2ee.GENERIC_PREVIEW, ts);
                _sendPeerNotification(com.silu.minigram.e2ee.E2ee.GENERIC_PUSH, "");
            }
            @Override public void plainFallback() {
                if (msg.containsKey("e2ee_media_key")) {
                    Toast.makeText(ChatActivity.this, "Contact can't receive encrypted media yet", Toast.LENGTH_LONG).show();
                    return;
                }
                messagesRef.child(msgId).setValue(msg);
                ContentTrackingHelper.trackSent(myUid, type, plainText,
                    ("image".equals(type) || "file".equals(type)) ? String.valueOf(msg.get("image_url")) : null,
                    "voice".equals(type) ? String.valueOf(msg.get("voice_url")) : null,
                    "chat", chatId, msgId);
                _updateUserChats(preview, ts);
                _sendPeerNotification(preview, pushImageUrl);
            }
            @Override public void error(String message) {
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
                _showEncryptionState();
            }
        });
    }

    private static String _filePreview(String metaJson) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(metaJson);
            String k = o.optString("k");
            String icon = "video".equals(k) ? "\uD83C\uDFAC " : "audio".equals(k) ? "\uD83C\uDFA7 " : "\uD83D\uDCC4 ";
            return icon + o.optString("n", "File");
        } catch (Exception e) { return "\uD83D\uDCC4 File"; }
    }

    private void _updateUserChats(String preview, long ts) {
        HashMap<String, Object> myChat = new HashMap<>();
        myChat.put("peer_uid",  peerUid);
        myChat.put("last_msg",  preview);
        myChat.put("last_time", ts);
        userChatsRef.child(myUid).child(chatId).updateChildren(myChat);

        userChatsRef.child(peerUid).child(chatId).child("unread")
            .setValue(ServerValue.increment(1));

        HashMap<String, Object> peerMeta = new HashMap<>();
        peerMeta.put("peer_uid",  myUid);
        peerMeta.put("last_msg",  preview);
        peerMeta.put("last_time", ts);
        userChatsRef.child(peerUid).child(chatId).updateChildren(peerMeta);
    }

    /**
     * Peer ko push bhejta hai.
     *
     * FIXES vs purana version:
     *  • Cached token khali hone par ab turant return nahi karta — ek baar DB se
     *    dobara padhta hai. Pehle pehla message (jab listener abhi fire hi nahi
     *    hua tha) hamesha chup-chaap drop ho jaata tha.
     *  • sender_uid / to_uid bheje jaate hain — notification se quick reply ka
     *    target sahi milta hai aur server stale token clean kar sakta hai.
     *  • Har failure log hoti hai.
     */
    private void _sendPeerNotification(final String previewText, final String imageUrl) {
        if (peerUid == null || peerUid.isEmpty()) return;

        _dispatchPeerPush("", previewText, imageUrl);
    }

    /** Apna display name cache — har message par DB read nahi karna padta. */
    private String cachedMyName = null;

    private void _dispatchPeerPush(final String token, final String previewText, final String imageUrl) {
        if (cachedMyName != null && !cachedMyName.isEmpty()) {
            _postPush(token, cachedMyName, previewText, imageUrl);
            return;
        }
        database.getReference("users").child(myUid).child("fullname")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String myName = snap.getValue(String.class);
                    cachedMyName = (myName == null || myName.isEmpty()) ? "Someone" : myName;
                    _postPush(token, cachedMyName, previewText, imageUrl);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    _postPush(token, "Someone", previewText, imageUrl);
                }
            });
    }

    private void _postPush(String token, String myName, String previewText, String imageUrl) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("title",      myName);
        data.put("body",       previewText != null ? previewText : "");
        data.put("image",      imageUrl != null ? imageUrl : "");
        data.put("type",       "chat");
        data.put("extra",      chatId);
        data.put("sender_uid", myUid);    // notification se reply karne ka target
        data.put("to_uid",     peerUid);  // server stale token cleanup kar sake
        FCMNotificationSender.sendNotification(data, "", token,
            new FCMNotificationSender.OnResponse() {
                @Override public void onSuccess(String r) { Log.d("MinigramFCM", "chat push ok"); }
                @Override public void onError(String e)   { Log.e("MinigramFCM", "chat push FAILED: " + e); }
            });
    }

    private void scrollToMessage(String msgId) {
        for (int i = 0; i < messageList.size(); i++) {
            if (msgId.equals(messageList.get(i).get("msgId"))) {
                highlightedMsgId = msgId;
                adapter.setHighlightedMsgId(msgId);
                adapter.notifyMessageChanged(msgId);
                final int pos = i;
                // RecyclerView's LinearSmoothScroller (unlike ListView) can
                // smooth-scroll correctly to an arbitrary, possibly off-
                // screen, variable-height item in one step — no more jump-
                // then-smooth-scroll workaround, and no transcript-mode
                // dance needed since DiffUtil-based updates never reset
                // scroll position in the first place.
                listview1.post(() -> _smoothScrollToPositionWithOffset(pos, dp(80)));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    String clearedId = highlightedMsgId;
                    highlightedMsgId = null;
                    adapter.setHighlightedMsgId(null);
                    // Re-look-up by id rather than reusing pos — new messages
                    // may have shifted this row's position during the 2.5s
                    // the highlight was showing.
                    if (clearedId != null) adapter.notifyMessageChanged(clearedId);
                }, 2500);
                break;
            }
        }
    }

    /**
     * Smooth-scrolls RecyclerView so the item at `position` lands `offsetPx`
     * from the top of the viewport — the RecyclerView equivalent of
     * ListView's smoothScrollToPositionFromTop(pos, offset, duration).
     * LinearSmoothScroller handles arbitrary distances and variable row
     * heights correctly in one pass, so no manual jump-then-scroll dance.
     */
    private void _smoothScrollToPositionWithOffset(int position, int offsetPx) {
        LinearSmoothScroller smoothScroller = new LinearSmoothScroller(this) {
            @Override protected int getVerticalSnapPreference() { return SNAP_TO_START; }
            @Override public int calculateDyToMakeVisible(View view, int snapPreference) {
                int dy = super.calculateDyToMakeVisible(view, snapPreference);
                return dy - offsetPx;
            }
        };
        smoothScroller.setTargetPosition(position);
        chatLayoutManager.startSmoothScroll(smoothScroller);
    }

    private void _setReplyMode(String msgId, String preview, String senderName) {
        replyToId    = msgId;
        replyPreview = preview;
        if (layout_reply_bar != null) {
            layout_reply_bar.setVisibility(View.VISIBLE);
            if (txt_reply_preview != null)
                txt_reply_preview.setText(senderName + ": " + preview);
        }
        edit_msg.requestFocus();
    }

    private void _clearReply() {
        replyToId    = null;
        replyPreview = null;
        if (layout_reply_bar != null) layout_reply_bar.setVisibility(View.GONE);
    }

    private void _showMsgActionSheet(HashMap<String, Object> msg) {
        final String  mId   = (String) msg.get("msgId");
        if (mId != null && mId.startsWith("temp_")) return;
        final String  fText = ("sticker".equals(msg.get("type")) || "file".equals(msg.get("type"))) ? ""
            : ("image".equals(msg.get("type")) || "voice".equals(msg.get("type")))
            ? (String) msg.get("caption") : (String) msg.get("text");
        final String  fImg  = (String) msg.get("image_url");
        final String  fSndr = (String) msg.get("sender_uid");
        final boolean fPin  = Boolean.TRUE.equals(msg.get("is_pinned"));
        final String  fType = msg.get("type") != null ? (String) msg.get("type") : "text";
        if (mId == null) return;

        MsgActionSheet sheet = MsgActionSheet.newInstance(mId,
            fText != null ? fText : "",
            fImg  != null ? fImg  : "",
            fSndr, myUid, peerName, fPin, fType);
        sheet.setListener(new MsgActionSheet.ActionListener() {
            @Override public void onCopy(String t) {
                SensitiveClip.copy(ChatActivity.this, "msg", t);
                Toast.makeText(ChatActivity.this, "Copied", Toast.LENGTH_SHORT).show();
            }
            @Override public void onDeleteForMe(String id) {
                messagesRef.child(id).child("deleted_for_" + myUid).setValue(true);
            }
            @Override public void onDeleteForAll(String id) {
                HashMap<String, Object> u = new HashMap<>();
                u.put("is_deleted_for_all", true);
                u.put("text",""); u.put("image_url",""); u.put("caption","");
                messagesRef.child(id).updateChildren(u);
                _updateUserChats("\uD83D\uDEAB This message was deleted", System.currentTimeMillis());
            }
            @Override public void onEdit(String id, String cur) { _showEditDialog(id, cur, fType); }
            @Override public void onPin(String id, boolean curPin) {
                messagesRef.child(id).child("is_pinned").setValue(!curPin);
            }
            @Override public void onReply(String id, String preview, String sName) {
                _setReplyMode(id, preview, getReplySenderLabel(id, sName));
            }
            @Override public void onForward(String t, String iUrl) {
                List<HashMap<String, Object>> list = new ArrayList<>();
                list.add(msg);
                forwardSelectedMessages(list);
            }
            @Override public void onSelect(String id) {
                if (mActionMode != null) return;
                selectedMsgs.add(id);
                mActionMode = startActionMode(mActionModeCallback);
                if (mActionMode != null) mActionMode.setTitle(selectedMsgs.size() + " selected");
                adapter.notifyMessageChanged(id);
            }
            @Override public void onShare(String t, String iUrl) {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, (t != null && !t.isEmpty()) ? t : String.valueOf(iUrl));
                startActivity(Intent.createChooser(s, "Share"));
            }
            @Override public void onInfo(String id) {
                Long ts = msg.get("timestamp") instanceof Long ? (Long) msg.get("timestamp") : null;
                String when = ts == null ? "-" : new java.text.SimpleDateFormat("d MMM yyyy, hh:mm:ss a", java.util.Locale.getDefault()).format(new java.util.Date(ts));
                boolean mine = myUid.equals(msg.get("sender_uid"));
                String st = String.valueOf(msg.get("status"));
                new android.app.AlertDialog.Builder(ChatActivity.this).setTitle("Message info")
                    .setMessage((mine ? "Sent: " : "Received: ") + when + (mine ? "\nStatus: " + ("read".equals(st) ? "Read" : "delivered".equals(st) ? "Delivered" : "Sent") : "")
                        + "\nType: " + fType + (useE2ee ? "\nEncryption: end-to-end" : ""))
                    .setPositiveButton("OK", null).show();
            }
            @Override public void onReact(String id, String emoji) {
                // Toggle: tapping the same emoji again removes it.
                DatabaseReference rRef = messagesRef.child(id).child("reactions").child(myUid);
                rRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (emoji.equals(snap.getValue(String.class))) rRef.removeValue();
                        else rRef.setValue(emoji);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
            }
        });
        sheet.show(getSupportFragmentManager(), "msg_action");
    }

    /** Edits in an encrypted chat: the new text is encrypted and replaces the ciphertext. */
    private void _editEncrypted(final String msgId, final String newText) {
        HashMap<String, Object> cur = null;
        for (HashMap<String, Object> m : messageList) if (msgId.equals(m.get("msgId"))) { cur = m; break; }
        if (cur == null) return;
        HashMap<String, Object> msg = new HashMap<>();
        String type = String.valueOf(cur.get("type"));
        msg.put("type", type); msg.put("sender_uid", myUid); msg.put("timestamp", cur.get("timestamp"));
        if ("text".equals(type)) { msg.put("text", newText); msg.put("caption", ""); }
        else { msg.put("text", ""); msg.put("caption", newText); }
        for (String k : new String[]{"reply_preview", "reply_sender", "link_preview_url", "link_preview_title",
                "link_preview_desc", "link_preview_image", "link_preview_site"})
            if (cur.get(k) != null) msg.put(k, cur.get(k));
        if (cur.get("e2ee_media_url") != null) {
            msg.put("e2ee_media_key", cur.get("e2ee_media_key"));
            msg.put("voice".equals(type) ? "voice_url" : "image_url", cur.get("e2ee_media_url"));
        }
        final com.silu.minigram.e2ee.E2ee e2 = com.silu.minigram.e2ee.E2ee.get(this, myUid);
        e2.prepareOutgoing(chatId, peerUid, msgId, msg, new com.silu.minigram.e2ee.E2ee.OutCb() {
            @Override public void ready(java.util.Map<String, Object> toStore) {
                HashMap<String, Object> upd = new HashMap<>();
                upd.put("ct", toStore.get("ct")); upd.put("e2ee", 1); upd.put("is_edited", true);
                if (toStore.get("x_ik") != null) { upd.put("x_ik", toStore.get("x_ik")); upd.put("x_ek", toStore.get("x_ek")); upd.put("x_spk", toStore.get("x_spk")); }
                messagesRef.child(msgId).updateChildren(upd);
            }
            @Override public void plainFallback() { Toast.makeText(ChatActivity.this, "Can't edit right now", Toast.LENGTH_SHORT).show(); }
            @Override public void error(String m) { Toast.makeText(ChatActivity.this, m, Toast.LENGTH_LONG).show(); }
        });
    }

    private void _showEditDialog(String msgId, String currentText, String msgType) {
        EditText input = new EditText(this);
        input.setText(currentText);
        input.setSelection(currentText.length());
        input.setPadding(48, 24, 48, 24);
        new AlertDialog.Builder(this).setTitle("Edit Message").setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String nt = input.getText().toString().trim();
                if (!nt.isEmpty()) {
                    if (useE2ee) { _editEncrypted(msgId, nt); return; }
                    HashMap<String, Object> upd = new HashMap<>();
                    upd.put("text", nt); upd.put("caption", nt); upd.put("is_edited", true);
                    messagesRef.child(msgId).updateChildren(upd);
                }
            }).setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == 2001 && res == RESULT_OK && data != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                android.content.ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            if (uris.size() == 1) {
                ArrayList<String> strs = new ArrayList<>();
                strs.add(uris.get(0).toString());
                Intent intent = new Intent(this, ImageCaptionActivity.class);
                intent.putStringArrayListExtra(ImageCaptionActivity.EXTRA_IMAGE_URIS, strs);
                startActivityForResult(intent, ImageCaptionActivity.REQUEST_CODE);
            } else {
                for (Uri u : uris) _sendImageInstant(u, "");
            }
            return;
        }

        if (req == ImageCaptionActivity.REQUEST_CODE && res == RESULT_OK && data != null) {
            ArrayList<String> uriStrings =
                data.getStringArrayListExtra(ImageCaptionActivity.EXTRA_IMAGE_URIS);
            String caption =
                data.getStringExtra(ImageCaptionActivity.EXTRA_CAPTION);
            if (uriStrings != null) {
                for (String uriStr : uriStrings) {
                    _sendImageInstant(Uri.parse(uriStr), caption != null ? caption : "");
                }
            }
        }
    }

    /**
     * Opens the dedicated Forward screen (ForwardChatListActivity) with the
     * given message(s) serialized as JSON.
     *
     * REPLACES the old in-place AlertDialog (_showForwardDialog) that used to
     * live here, which also did its own ad-hoc peer-name/photo lookup before
     * showing. That whole dance — and the dialog itself — is gone; the new
     * Activity owns target loading, search, multi-select, and (unlike the old
     * dialog) actually updates the recipient's chat list and sends them a
     * push notification. See ForwardChatListActivity's class doc for the full
     * list of bugs this fixes.
     */
    private void forwardSelectedMessages(List<HashMap<String, Object>> msgsToForward) {
        if (msgsToForward == null || msgsToForward.isEmpty()) return;
        Intent i = new Intent(this, ForwardChatListActivity.class);
        i.putExtra(ForwardChatListActivity.EXTRA_MESSAGES_JSON, new Gson().toJson(msgsToForward));
        startActivity(i);
    }


    private void _showPeerProfile() {
        Intent i = new Intent(this, MyProfileActivity.class);
        i.putExtra("target_uid", peerUid);
        i.putExtra("chat_id", chatId);
        startActivity(i);
    }

    /** Adds a menu item whose icon is drawn from the app's icon font.
     *  Falls back to a text-only item if the glyph can't be rendered, so a
     *  font problem can never leave the toolbar with unusable buttons. */
    private void _addMenuIcon(Menu menu, int id, String title, String ligature, int tint) {
        MenuItem item = menu.add(0, id, 0, title);
        android.graphics.drawable.Drawable icon = FontIconDrawable.create(this, ligature, tint);
        if (icon != null) item.setIcon(icon);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    private ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.setTitle("1 Selected");
            // Icons now come from the SAME Material Icons font the rest of
            // the app uses (see FontIconDrawable) instead of separate
            // drawable assets — those were the one place in the UI with a
            // different icon style and weight, which is why this toolbar
            // looked out of place.
            int tint = Ink.c("#229ED9");
            _addMenuIcon(menu, 1, "Forward",  "reply",        tint);
            _addMenuIcon(menu, 3, "Copy",     "content_copy", tint);
            _addMenuIcon(menu, 2, "Delete",   "delete",       tint);
            return true;
        }
        @Override public boolean onPrepareActionMode(ActionMode m, Menu menu) { return false; }
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == 1) { // Forward
                List<HashMap<String, Object>> cp = new ArrayList<>();
                for (HashMap<String, Object> msg : messageList)
                    if (selectedMsgs.contains(msg.get("msgId"))) cp.add(msg);
                Collections.sort(cp, (m1, m2) ->
                    Long.compare((Long) m1.get("timestamp"), (Long) m2.get("timestamp")));
                forwardSelectedMessages(cp);
                mode.finish();
                return true;
            }
            if (item.getItemId() == 2) { // Delete
                final List<String> ids = new ArrayList<>(selectedMsgs);
                boolean allMine = true;
                for (HashMap<String, Object> msg : messageList) {
                    if (selectedMsgs.contains(msg.get("msgId")) && !myUid.equals(msg.get("sender_uid"))) {
                        allMine = false;
                        break;
                    }
                }
                String[] options = allMine
                    ? new String[]{"Delete for me", "Delete for everyone"}
                    : new String[]{"Delete for me"};
                final boolean allMineFinal = allMine;
                new AlertDialog.Builder(ChatActivity.this)
                    .setTitle(ids.size() + " message" + (ids.size() > 1 ? "s" : ""))
                    .setItems(options, (d, which) -> {
                        boolean deleteForAll = allMineFinal && which == 1;
                        for (String id : ids) {
                            if (deleteForAll) {
                                HashMap<String, Object> u = new HashMap<>();
                                u.put("is_deleted_for_all", true);
                                u.put("text", ""); u.put("image_url", "");
                                u.put("voice_url", ""); u.put("caption", "");
                                messagesRef.child(id).updateChildren(u);
                            } else {
                                messagesRef.child(id).child("deleted_for_" + myUid).setValue(true);
                            }
                        }
                        if (deleteForAll) {
                            _updateUserChats("\uD83D\uDEAB This message was deleted", System.currentTimeMillis());
                        }
                        mode.finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            }
            if (item.getItemId() == 3) { // Copy
                List<HashMap<String, Object>> cp = new ArrayList<>();
                for (HashMap<String, Object> msg : messageList)
                    if (selectedMsgs.contains(msg.get("msgId"))) cp.add(msg);
                Collections.sort(cp, (m1, m2) ->
                    Long.compare((Long) m1.get("timestamp"), (Long) m2.get("timestamp")));
                StringBuilder sb = new StringBuilder();
                SimpleDateFormat sdf = new SimpleDateFormat("[HH:mm dd/MM/yy]", Locale.getDefault());
                for (HashMap<String, Object> msg : cp) {
                    String sender = myUid.equals(msg.get("sender_uid")) ? "Me" : peerName;
                    String txt = (String) msg.get("text");
                    if (txt == null || txt.isEmpty()) txt = (String) msg.get("caption");
                    if (txt == null || txt.isEmpty()) txt = "\uD83D\uDCF7 Photo";
                    sb.append(sdf.format(new Date((Long) msg.get("timestamp"))))
                        .append(" ").append(sender).append(": ").append(txt).append("\n");
                }
                SensitiveClip.copy(ChatActivity.this, "Copied Messages", sb.toString().trim());
                Toast.makeText(ChatActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                mode.finish();
                return true;
            }
            return false;
        }
        @Override public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
            java.util.List<String> cleared = new ArrayList<>(selectedMsgs);
            selectedMsgs.clear();
            // Repaint just the rows that were selected, rather than
            // rebuilding the list (which scrolled it).
            if (adapter != null) {
                for (String id : cleared) adapter.notifyMessageChanged(id);
            }
        }
    };

    private void toggleSelection(String id) {
        if (selectedMsgs.contains(id)) selectedMsgs.remove(id); else selectedMsgs.add(id);
        if (selectedMsgs.isEmpty() && mActionMode != null) mActionMode.finish();
        else if (mActionMode != null) mActionMode.setTitle(selectedMsgs.size() + " Selected");
        // Rebind ONLY the row that changed. refreshWithoutScroll() rebuilds
        // the whole list through DiffUtil, which is what made selecting an
        // item visibly refresh the list and jump to the end — selecting
        // shouldn't move the list at all.
        adapter.notifyMessageChanged(id);
    }

    @Override protected void onResume() {
        super.onResume();
        try {
            android.view.ViewGroup content = findViewById(android.R.id.content);
            if (content != null && content.getChildCount() > 0) content.getChildAt(0).setBackground(Appearance.wallpaperDrawable(this));
            if (adapter != null) adapter.notifyDataSetChanged();
        } catch (Throwable ignored) { }
        isFirstLoad   = true;
        currentChatId = chatId;
        _resetBubbleState();
        // Resync typing state from DB instead of blindly resetting it —
        // a ValueEventListener only fires on CHANGE, so if the peer was
        // already typing before we backgrounded/foregrounded the app, the
        // stale local flag would never get corrected until their next keystroke.
        if (myUid != null && chatId != null && database != null) {
            database.getReference("user_chats").child(myUid).child(chatId).child("is_typing")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        isPeerTyping = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                        if (isPeerTyping && txt_status != null) {
                            txt_status.setText("typing...");
                            txt_status.setTextColor(Ink.c("#4CAF50"));
                        } else if (peerUid != null && database != null) {
                            database.getReference("users").child(peerUid).child("status")
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                                        _updateStatusUI(s.getValue(String.class));
                                    }
                                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                                });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        } else {
            isPeerTyping = false;
        }
        if (myUid != null && database != null
                && getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
                    .getBoolean(PrivacySecurityActivity.KEY_ONLINE_VIS, true))
            database.getReference("users").child(myUid).child("status").setValue("online");
        boolean screenshotOff = getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
            .getBoolean(PrivacySecurityActivity.KEY_SCREENSHOT, false);
        if (screenshotOff)
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        if (chatId != null) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(chatId.hashCode());
        }
        if (chatId != null && userChatsRef != null && messagesRef != null)
            _markMessagesRead();
    }

    @Override protected void onPause() {
        super.onPause();
        currentChatId = null;
        isPeerTyping  = false;
        if (adapter != null) adapter.stopVoicePlayback();
        if (isRecording) _stopVoiceRecording(true); // discard in-progress recording if user backgrounds app
        if (myUid != null && database != null) {
            database.getReference("users").child(myUid).child("status").setValue("offline");
            if (getSharedPreferences(PrivacySecurityActivity.PREFS, MODE_PRIVATE)
                    .getBoolean(PrivacySecurityActivity.KEY_LAST_SEEN, true))
                database.getReference("users").child(myUid).child("last_active_ts")
                    .setValue(ServerValue.TIMESTAMP);
        }
        
        if (database != null && chatId != null && peerUid != null)
            database.getReference("user_chats").child(peerUid).child(chatId).child("is_typing").setValue(false);
        
        if (stopTypingRunnable != null) typingHandler.removeCallbacks(stopTypingRunnable);
        isCurrentlyTyping = false;

        _saveDraft();
    }

    // ── Draft save/restore — keeps unsent typed text per-chat ────────────────
    private static final String DRAFT_PREFS = "minigram_drafts";

    private void _saveDraft() {
        if (chatId == null || edit_msg == null) return;
        String text = edit_msg.getText().toString();
        SharedPreferences.Editor ed = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).edit();
        if (text.trim().isEmpty()) {
            ed.remove(chatId);
        } else {
            ed.putString(chatId, text);
        }
        ed.apply();
    }

    private void _restoreDraft() {
        if (chatId == null || edit_msg == null) return;
        String draft = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE).getString(chatId, "");
        if (draft != null && !draft.isEmpty() && edit_msg.getText().toString().isEmpty()) {
            edit_msg.setText(draft);
            edit_msg.setSelection(draft.length());
            if (btn_send_icon != null) btn_send_icon.setText("send");
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        currentChatId = null;
        if (messagesListener != null && messagesRef != null)
            messagesRef.removeEventListener(messagesListener);
        if (statusListener != null && peerUid != null && database != null)
            database.getReference("users").child(peerUid).child("status")
                .removeEventListener(statusListener);
        if (typingListener != null && chatId != null && myUid != null && database != null)
            database.getReference("user_chats").child(myUid).child(chatId).child("is_typing")
                .removeEventListener(typingListener);
        
        if (stopTypingRunnable != null) typingHandler.removeCallbacks(stopTypingRunnable);
        if (database != null && chatId != null && peerUid != null)
            database.getReference("user_chats").child(peerUid).child(chatId).child("is_typing").setValue(false);

        // FIX: ye listener pehle kabhi detach hota hi nahi tha — har chat khulne
        // par ek aur listener chipak jaata tha (memory leak + fizool traffic).

        if (connectionMonitor != null) connectionMonitor.stop();
        retryQueue.clear();
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }
}