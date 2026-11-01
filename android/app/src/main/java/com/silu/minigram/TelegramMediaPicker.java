package com.silu.minigram;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class TelegramMediaPicker extends BottomSheetDialogFragment {

    private static final int PERM_STORAGE  = 901;
    private static final int PERM_CAMERA   = 902;
    private static final int REQ_CAMERA    = 903;
    private static final int REQ_PREVIEW   = ImageCaptionActivity.REQUEST_CODE;

    public interface OnPickerListener {
        void onImagesPicked(ArrayList<String> uriStrings, String caption);
    }

    private GridView           gridView;
    private TextView           btnClose;
    private TextView           btnSend;
    private TextView           txtSelCount;

    private MediaPickerAdapter pickerAdapter;
    private final List<Uri>   allImages    = new ArrayList<>();
    private final List<Uri>   selectedUris = new ArrayList<>();

    private OnPickerListener   pickerListener;
    private Uri                cameraOutputUri;
    private final Handler      handler = new Handler(Looper.getMainLooper());

    public static TelegramMediaPicker newInstance() {
        return new TelegramMediaPicker();
    }

    public void setListener(OnPickerListener l) {
        this.pickerListener = l;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.AppBottomSheetDialogTheme);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                              ViewGroup container,
                              Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_media_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        gridView    = view.findViewById(R.id.grid_media);
        btnClose    = view.findViewById(R.id.btn_picker_close);
        btnSend     = view.findViewById(R.id.btn_picker_send);
        txtSelCount = view.findViewById(R.id.txt_sel_count);

        pickerAdapter = new MediaPickerAdapter(requireContext(), allImages, selectedUris,
            new MediaPickerAdapter.OnCellActionListener() {

                @Override
                public void onCameraClicked() {
                    _openCamera();
                }

                @Override
                public void onImageToggled(Uri uri, boolean selected) {
                    if (selected) {
                        if (!selectedUris.contains(uri)) selectedUris.add(uri);
                    } else {
                        selectedUris.remove(uri);
                    }
                    _updateSendButton();
                    pickerAdapter.notifyDataSetChanged();
                }

                @Override
                public void onImageLongPressed(Uri uri) {
                    ArrayList<String> list = new ArrayList<>();
                    list.add(uri.toString());
                    _openPreviewActivity(list);
                }
            });

        gridView.setAdapter(pickerAdapter);

        gridView.getViewTreeObserver().addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    gridView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    int colWidth = gridView.getWidth() / 3 - dp(2);
                    if (colWidth > 0) {
                        pickerAdapter.setCellSize(colWidth);
                    }
                }
            });

        btnClose.setOnClickListener(v -> dismiss());

        btnSend.setOnClickListener(v -> {
            if (selectedUris.isEmpty()) return;
            _onSendTapped();
        });

        _updateSendButton();
        _checkPermissionsAndLoad();
    }

    @Override
    public void onStart() {
        super.onStart();
        View sheet = getDialog() != null
            ? getDialog().getWindow().findViewById(
                com.google.android.material.R.id.design_bottom_sheet)
            : null;
        if (sheet != null) {
            ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            sheet.setLayoutParams(lp);
            BottomSheetBehavior.from(sheet).setState(BottomSheetBehavior.STATE_EXPANDED);
            BottomSheetBehavior.from(sheet).setSkipCollapsed(true);
        }
    }

    private void _checkPermissionsAndLoad() {
        String perm;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perm = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        }
        if (ContextCompat.checkSelfPermission(requireContext(), perm)
                == PackageManager.PERMISSION_GRANTED) {
            _loadMediaStoreImages();
        } else {
            requestPermissions(new String[]{perm}, PERM_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                            @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        if (requestCode == PERM_STORAGE
            && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            _loadMediaStoreImages();

        } else if (requestCode == PERM_CAMERA
            && grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            _openCamera();

        } else {
            Toast.makeText(getContext(), "Permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    private void _loadMediaStoreImages() {
        new Thread(() -> {
            List<Uri> images = new ArrayList<>();
            String[] proj = {MediaStore.Images.Media._ID};
            try {
                Cursor cursor = requireContext().getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (cursor != null) {
                    int col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(col);
                        images.add(android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            handler.post(() -> {
                allImages.clear();
                allImages.addAll(images);
                if (pickerAdapter != null) pickerAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void _openCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, PERM_CAMERA);
            return;
        }
        try {
            File photoFile = _createTempImageFile();
            cameraOutputUri = FileProvider.getUriForFile(requireContext(),
                requireContext().getPackageName() + ".provider", photoFile);
            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (IllegalArgumentException | NullPointerException metaDataErr) {
            Toast.makeText(getContext(), "Manifest Error: Missing FileProvider meta-data", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(getContext(), "Could not open camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private File _createTempImageFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File dir = requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
        if (dir != null && !dir.exists()) dir.mkdirs();
        return File.createTempFile("CAM_" + ts + "_", ".jpg", dir);
    }

    private void _onSendTapped() {
        ArrayList<String> uriStrings = new ArrayList<>();
        for (Uri u : selectedUris) uriStrings.add(u.toString());
        _openPreviewActivity(uriStrings);
    }

    private void _openPreviewActivity(ArrayList<String> uriStrings) {
        Intent intent = new Intent(requireContext(), ImageCaptionActivity.class);
        intent.putStringArrayListExtra(ImageCaptionActivity.EXTRA_IMAGE_URIS, uriStrings);
        startActivityForResult(intent, REQ_PREVIEW);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAMERA && resultCode == android.app.Activity.RESULT_OK) {
            if (cameraOutputUri != null) {
                allImages.add(0, cameraOutputUri);
                selectedUris.clear();
                selectedUris.add(cameraOutputUri);
                pickerAdapter.notifyDataSetChanged();
                _updateSendButton();
                ArrayList<String> list = new ArrayList<>();
                list.add(cameraOutputUri.toString());
                _openPreviewActivity(list);
            }
            return;
        }

        if (requestCode == REQ_PREVIEW
            && resultCode == android.app.Activity.RESULT_OK
            && data != null) {

            ArrayList<String> uriStrings =
                data.getStringArrayListExtra(ImageCaptionActivity.EXTRA_IMAGE_URIS);
            String caption =
                data.getStringExtra(ImageCaptionActivity.EXTRA_CAPTION);

            if (uriStrings != null && !uriStrings.isEmpty()) {
                if (pickerListener != null) {
                    pickerListener.onImagesPicked(uriStrings, caption != null ? caption : "");
                }
                dismiss();
            }
        }
    }

    private void _updateSendButton() {
        if (selectedUris.isEmpty()) {
            btnSend.setAlpha(0.4f);
            btnSend.setClickable(false);
            txtSelCount.setVisibility(View.GONE);
        } else {
            btnSend.setAlpha(1.0f);
            btnSend.setClickable(true);
            txtSelCount.setVisibility(View.VISIBLE);
            txtSelCount.setText(String.valueOf(selectedUris.size()));

            android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(Ink.c("#1A73E8"));
            txtSelCount.setBackground(circle);
            txtSelCount.setTextColor(Color.WHITE);
        }
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }
}