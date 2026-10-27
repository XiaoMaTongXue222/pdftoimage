package com.mzj.pdftoimage;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PdfToImage";
    private static final String SAVE_DIR = "PdfToImage";
    private static final int REQ_WRITE = 100;

    private Uri pdfUri;
    private String baseName = "pdf";
    private int pageCount = 0;

    private TextView tvFile;
    private TextView tvPages;
    private TextView tvProgress;
    private EditText etStart;
    private EditText etEnd;
    private View llRange;
    private View llProgress;
    private Button btnPick;
    private Button btnConvert;
    private ProgressBar progressBar;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> pickLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    onPdfPicked(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFile = findViewById(R.id.tvFile);
        tvPages = findViewById(R.id.tvPages);
        tvProgress = findViewById(R.id.tvProgress);
        etStart = findViewById(R.id.etStart);
        etEnd = findViewById(R.id.etEnd);
        llRange = findViewById(R.id.llRange);
        llProgress = findViewById(R.id.llProgress);
        btnPick = findViewById(R.id.btnPick);
        btnConvert = findViewById(R.id.btnConvert);
        progressBar = findViewById(R.id.progressBar);

        RadioGroup rgRange = findViewById(R.id.rgRange);
        rgRange.setOnCheckedChangeListener((group, id) ->
                llRange.setVisibility(id == R.id.rbCustom ? View.VISIBLE : View.GONE));

        btnPick.setOnClickListener(v -> pickLauncher.launch(new String[]{"application/pdf"}));
        btnConvert.setOnClickListener(v -> startConvert());

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
        }

        handleIncoming();
    }

    private void handleIncoming() {
        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            onPdfPicked(intent.getData());
        }
    }

    private void onPdfPicked(Uri uri) {
        pdfUri = uri;
        baseName = stripExt(getDisplayName(uri));
        tvFile.setText("已选择：" + baseName + ".pdf");
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
             PdfRenderer renderer = new PdfRenderer(fd)) {
            pageCount = renderer.getPageCount();
            tvPages.setText("页数：" + pageCount);
        } catch (Exception e) {
            pageCount = 0;
            tvPages.setText("页数：该文件无法解析，请换一个 PDF");
            Log.e(TAG, "open pdf failed", e);
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                return cursor.getString(idx);
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "pdf" : last;
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void startConvert() {
        if (pdfUri == null) {
            Toast.makeText(this, "请先选择 PDF 文件", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pageCount <= 0) {
            Toast.makeText(this, "当前文件无法解析", Toast.LENGTH_SHORT).show();
            return;
        }

        int start = 1;
        int end = pageCount;
        RadioGroup rgRange = findViewById(R.id.rgRange);
        if (rgRange.getCheckedRadioButtonId() != R.id.rbAll) {
            int s = parseInt(etStart.getText().toString(), 1);
            int e = parseInt(etEnd.getText().toString(), pageCount);
            start = Math.min(s, e);
            end = Math.max(s, e);
        }
        start = Math.max(1, Math.min(start, pageCount));
        end = Math.max(start, Math.min(end, pageCount));

        RadioGroup rgFormat = findViewById(R.id.rgFormat);
        boolean png = rgFormat.getCheckedRadioButtonId() == R.id.rbPng;
        int scale = getScale();

        setBusy(true);
        final int from = start;
        final int to = end;
        executor.execute(() -> doConvert(from, to, png, scale));
    }

    private int getScale() {
        RadioGroup rgScale = findViewById(R.id.rgScale);
        int id = rgScale.getCheckedRadioButtonId();
        if (id == R.id.rb1x) {
            return 1;
        }
        return id == R.id.rb2x ? 2 : 3;
    }

    private int parseInt(String text, int def) {
        if (text == null || text.trim().isEmpty()) {
            return def;
        }
        return Integer.parseInt(text.trim());
    }

    private void doConvert(int start, int end, boolean png, int scale) {
        int total = end - start + 1;
        int done = 0;
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(pdfUri, "r");
             PdfRenderer renderer = new PdfRenderer(fd)) {
            for (int index = start - 1; index <= end - 1; index++) {
                PdfRenderer.Page page = renderer.openPage(index);
                int width = page.getWidth() * scale;
                int height = page.getHeight() * scale;
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                String fileName = baseName + "_" + String.format("%03d", index + 1)
                        + (png ? ".png" : ".jpg");
                saveBitmap(bitmap, fileName, png);
                bitmap.recycle();
                page.close();
                done++;
                final int cur = done;
                mainHandler.post(() -> updateProgress(cur, total));
            }
        } catch (Exception e) {
            Log.e(TAG, "convert failed", e);
            String msg = e.getMessage();
            mainHandler.post(() -> {
                setBusy(false);
                tvProgress.setText("转换失败：" + msg);
            });
            return;
        }
        final int ok = done;
        mainHandler.post(() -> {
            setBusy(false);
            tvProgress.setText("已完成 " + ok + " / " + total + " 页");
            showResult(ok);
        });
    }

    private void updateProgress(int cur, int total) {
        progressBar.setMax(total);
        progressBar.setProgress(cur);
        tvProgress.setText("转换中 " + cur + " / " + total + " 页");
    }

    private void saveBitmap(Bitmap bitmap, String fileName, boolean png) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, png ? "image/png" : "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + SAVE_DIR);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    SAVE_DIR);
            dir.mkdirs();
            values.put(MediaStore.MediaColumns.DATA, new File(dir, fileName).getAbsolutePath());
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        OutputStream out = getContentResolver().openOutputStream(uri);
        bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, out);
        out.close();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues finish = new ContentValues();
            finish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, finish, null, null);
        }
    }

    private void showResult(int count) {
        new AlertDialog.Builder(this)
                .setTitle("转换完成")
                .setMessage("共导出 " + count + " 张图片\n保存位置：Pictures/" + SAVE_DIR)
                .setPositiveButton("知道了", null)
                .setNeutralButton("打开相册", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                    startActivity(intent);
                })
                .show();
    }

    private void setBusy(boolean busy) {
        btnPick.setEnabled(!busy);
        btnConvert.setEnabled(!busy);
        llProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            progressBar.setProgress(0);
            tvProgress.setText("准备中...");
        }
    }
}
