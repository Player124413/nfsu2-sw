package com.nfsu2x;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Small, dependency-free launcher. The user selects an extracted, legally
 * owned game directory or Xbox XISO through the Storage Access Framework; the
 * launcher copies/extracts it into app-private storage so the native title can
 * use ordinary POSIX paths. No broad storage permission is requested.
 */
public final class LauncherActivity extends Activity {
    private static final int PICK_GAME_DIRECTORY = 42;
    private static final int PICK_GAME_IMAGE = 43;
    private static final String PREFS = "launcher";
    private static final String PREF_TREE = "game_tree";
    private static final String PREF_SCALE = "render_scale";
    private static final String PREF_VSYNC = "vsync";

    private final Handler handler = new Handler();
    private TextView status;
    private Button play;
    private ProgressBar progress;
    private Button copyLog;
    private Spinner scale;
    private android.widget.Switch vsync;
    private SharedPreferences prefs;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private Button action(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(48));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackgroundColor(Color.rgb(240, 68, 78));
        return b;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 9, 13));
        getWindow().setNavigationBarColor(Color.rgb(8, 9, 13));
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        installCrashLogHandler();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 9, 13));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(18), dp(28), dp(18));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -1));

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.nfsu2x.R.drawable.nfsu2_logo);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        page.addView(logo, new LinearLayout.LayoutParams(-1, dp(118)));

        TextView subtitle = label("XBOX STATIC RECOMPILATION  •  ANDROID ARM64", 11,
                Color.rgb(154, 164, 180));
        subtitle.setGravity(Gravity.CENTER);
        page.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        panel.setBackgroundColor(Color.rgb(23, 26, 35));
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(-1, -2);
        panelLp.setMargins(0, dp(14), 0, 0);
        page.addView(panel, panelLp);

        TextView heading = label("Быстрый старт", 18, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, dp(32)));
        TextView hint = label("Выберите папку с распакованной игрой или Xbox ISO/XISO. "
                + "Нужны default.xbe и каталог NFSUNDER.", 13, Color.rgb(190, 197, 210));
        hint.setLineSpacing(0, 1.12f);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, dp(48)));

        Button choose = action("Выбрать папку игры");
        panel.addView(choose, new LinearLayout.LayoutParams(-1, dp(50)));
        choose.setOnClickListener(v -> chooseGameDirectory());

        Button chooseIso = action("Установить ISO / XISO");
        LinearLayout.LayoutParams isoLp = new LinearLayout.LayoutParams(-1, dp(50));
        isoLp.topMargin = dp(8);
        panel.addView(chooseIso, isoLp);
        chooseIso.setOnClickListener(v -> chooseGameImage());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(8));
        progressLp.topMargin = dp(10);
        panel.addView(progress, progressLp);

        status = label("Папка не выбрана", 12, Color.rgb(154, 164, 180));
        status.setPadding(0, dp(10), 0, 0);
        panel.addView(status, new LinearLayout.LayoutParams(-1, dp(34)));

        LinearLayout settings = new LinearLayout(this);
        settings.setGravity(Gravity.CENTER_VERTICAL);
        settings.setPadding(0, dp(8), 0, 0);
        TextView quality = label("Рендер", 13, Color.rgb(190, 197, 210));
        settings.addView(quality, new LinearLayout.LayoutParams(0, dp(48), 1));
        scale = new Spinner(this);
        String[] scales = { "0.75x • экономия батареи", "1.0x • рекомендовано", "1.25x • качество" };
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, scales);
        scale.setAdapter(adapter);
        float savedScale = prefs.getFloat(PREF_SCALE, 1.0f);
        scale.setSelection(savedScale < 0.9f ? 0 : savedScale > 1.1f ? 2 : 1);
        settings.addView(scale, new LinearLayout.LayoutParams(dp(220), dp(48)));
        panel.addView(settings);

        vsync = new android.widget.Switch(this);
        vsync.setText("Синхронизация 60 FPS");
        vsync.setTextColor(Color.rgb(190, 197, 210));
        vsync.setTextSize(13);
        vsync.setChecked(prefs.getBoolean(PREF_VSYNC, true));
        panel.addView(vsync, new LinearLayout.LayoutParams(-1, dp(48)));

        play = action("ИГРАТЬ");
        play.setEnabled(false);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(-1, dp(54));
        playLp.topMargin = dp(18);
        page.addView(play, playLp);
        play.setOnClickListener(v -> startGame());

        copyLog = action("Скопировать log.txt");
        copyLog.setEnabled(false);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(46));
        logLp.topMargin = dp(8);
        page.addView(copyLog, logLp);
        copyLog.setOnClickListener(v -> copyLogToClipboard());

        TextView footer = label("Сенсорные кнопки включаются автоматически в игре. "
                + "Для максимальной производительности закройте фоновые приложения.",
                11, Color.rgb(120, 130, 148));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(14), 0, 0);
        page.addView(footer, new LinearLayout.LayoutParams(-1, dp(42)));

        setContentView(scroll);
        refreshState();
    }

    private void chooseGameDirectory() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_GAME_DIRECTORY);
    }

    private void chooseGameImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream", "application/x-iso9660-image", "*/*"});
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_GAME_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != PICK_GAME_DIRECTORY && requestCode != PICK_GAME_IMAGE)
                || resultCode != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri selected = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(selected,
                    data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers do not offer persistable permissions;
            // the copy below still works for this launch.
        }
        if (requestCode == PICK_GAME_DIRECTORY) {
            prefs.edit().putString(PREF_TREE, selected.toString()).apply();
            copyGameInBackground(selected);
        } else {
            extractGameImageInBackground(selected);
        }
    }

    private void copyGameInBackground(final Uri tree) {
        play.setEnabled(false);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        status.setText("Проверка и копирование игры…");
        new Thread(() -> {
            File destination = new File(getFilesDir(), "game");
            File staging = new File(getFilesDir(), "game.partial");
            boolean ok = false;
            try {
                deleteRecursive(staging);
                if (!staging.mkdirs() && !staging.isDirectory())
                    throw new IllegalStateException("cannot create private game directory");
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                copyChildren(tree, rootId, staging);
                validateGame(staging);
                deleteRecursive(destination);
                if (!staging.renameTo(destination))
                    throw new IllegalStateException("could not commit the copied game");
                ok = true;
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "unknown error" : e.getMessage();
                handler.post(() -> setStatus("Ошибка: " + message, false));
                deleteRecursive(staging);
            }
            if (ok)
                handler.post(() -> setStatus("Игра готова • данные в защищённом хранилище", true));
        }, "game-copy").start();
    }

    private void extractGameImageInBackground(final Uri image) {
        play.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        status.setText("Распаковка ISO/XISO…");
        new Thread(() -> {
            File destination = new File(getFilesDir(), "game");
            File staging = new File(getFilesDir(), "game.partial");
            boolean ok = false;
            try {
                deleteRecursive(staging);
                if (!staging.mkdirs() && !staging.isDirectory())
                    throw new IllegalStateException("cannot create private game directory");
                IsoExtractor.extract(getContentResolver(), image, staging,
                        percent -> handler.post(() -> progress.setProgress(percent)));
                validateGame(staging);
                deleteRecursive(destination);
                if (!staging.renameTo(destination))
                    throw new IllegalStateException("could not commit extracted game");
                ok = true;
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "unknown error" : e.getMessage();
                handler.post(() -> setStatus("Ошибка ISO: " + message, false));
                deleteRecursive(staging);
            }
            if (ok)
                handler.post(() -> setStatus("ISO установлена • игра готова", true));
        }, "xiso-extract").start();
    }

    private static void validateGame(File root) throws Exception {
        File xbe = findCaseInsensitive(root, "default.xbe");
        File data = findChildCaseInsensitive(root, "NFSUNDER");
        if (xbe == null || !xbe.isFile() || xbe.length() < 64)
            throw new IllegalStateException("default.xbe not found at the image root");
        if (data == null || !data.isDirectory())
            throw new IllegalStateException("NFSUNDER directory not found at the image root");
        // The native loader intentionally uses a stable lowercase name.
        if (!xbe.getName().equals("default.xbe")) {
            File lower = new File(root, "default.xbe");
            if (!xbe.renameTo(lower))
                throw new IllegalStateException("could not normalize default.xbe");
        }
    }

    private void copyChildren(Uri tree, String documentId, File target) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE };
        android.database.Cursor c = getContentResolver().query(children, projection, null, null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " COLLATE NOCASE");
        if (c == null)
            throw new IllegalStateException("document provider returned no children");
        try {
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null || name.contains("..") || name.contains("/"))
                    continue;
                File out = new File(target, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!out.mkdirs() && !out.isDirectory())
                        throw new IllegalStateException("cannot create " + name);
                    copyChildren(tree, id, out);
                } else {
                    copyDocument(DocumentsContract.buildDocumentUriUsingTree(tree, id), out);
                }
            }
        } finally {
            c.close();
        }
    }

    private void copyDocument(Uri uri, File out) throws Exception {
        File partial = new File(out.getPath() + ".partial");
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null)
            throw new IllegalStateException("cannot open " + out.getName());
        try (InputStream source = raw;
             BufferedInputStream in = new BufferedInputStream(source);
             BufferedOutputStream stream = new BufferedOutputStream(new FileOutputStream(partial))) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1)
                stream.write(buffer, 0, n);
        }
        if (!partial.renameTo(out))
            throw new IllegalStateException("cannot finish " + out.getName());
    }

    private static File findCaseInsensitive(File root, String name) {
        File[] children = root.listFiles();
        if (children == null)
            return null;
        for (File child : children) {
            if (child.isFile() && child.getName().toLowerCase(Locale.US).equals(name))
                return child;
        }
        return null;
    }

    private static File findChildCaseInsensitive(File root, String name) {
        File[] children = root.listFiles();
        if (children == null)
            return null;
        for (File child : children) {
            if (child.getName().toLowerCase(Locale.US).equals(name.toLowerCase(Locale.US)))
                return child;
        }
        return null;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists())
            return;
        File[] children = f.listFiles();
        if (children != null)
            for (File child : children)
                deleteRecursive(child);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private void refreshState() {
        File game = new File(getFilesDir(), "game/default.xbe");
        if (game.isFile() && game.length() >= 64)
            setStatus("Игра готова • " + game.length() / (1024 * 1024) + " MB", true);
        else
            setStatus("Выберите папку с распакованной игрой или ISO/XISO", false);
        updateLogButton();
    }

    private void setStatus(String text, boolean ready) {
        status.setText(text);
        status.setTextColor(ready ? Color.rgb(105, 218, 151) : Color.rgb(154, 164, 180));
        progress.setVisibility(View.GONE);
        play.setEnabled(ready);
        updateLogButton();
    }

    private void updateLogButton() {
        if (copyLog != null)
            copyLog.setEnabled(new File(getFilesDir(), "nfsu2_log.txt").isFile());
    }

    private void copyLogToClipboard() {
        File log = new File(getFilesDir(), "nfsu2_log.txt");
        if (!log.isFile()) {
            status.setText("Лог пока не создан");
            return;
        }
        try {
            long length = Math.min(log.length(), 4L * 1024 * 1024);
            byte[] data = new byte[(int) length];
            int used = 0;
            try (FileInputStream input = new FileInputStream(log)) {
                while (used < data.length) {
                    int n = input.read(data, used, data.length - used);
                    if (n < 0)
                        break;
                    used += n;
                }
            }
            String text = new String(data, 0, used, StandardCharsets.UTF_8);
            if (log.length() > length)
                text = "[log truncated to 4 MiB]\n" + text;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("nfsu2_log.txt", text));
            status.setText("log.txt скопирован в буфер обмена");
        } catch (Exception e) {
            status.setText("Не удалось скопировать лог: " + e.getMessage());
        }
    }

    private void installCrashLogHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            File log = new File(getFilesDir(), "nfsu2_log.txt");
            try (FileOutputStream output = new FileOutputStream(log, true)) {
                StringWriter text = new StringWriter();
                error.printStackTrace(new PrintWriter(text));
                output.write(("\n[JAVA CRASH] " + thread.getName() + "\n"
                        + text + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (Exception ignored) {
                // Preserve the original crash behaviour if the log cannot be written.
            }
            if (previous != null)
                previous.uncaughtException(thread, error);
        });
    }

    private void startGame() {
        float renderScale = scale.getSelectedItemPosition() == 0 ? 0.75f
                : scale.getSelectedItemPosition() == 2 ? 1.25f : 1.0f;
        prefs.edit().putFloat(PREF_SCALE, renderScale)
                .putBoolean(PREF_VSYNC, vsync.isChecked()).apply();
        Intent game = new Intent(this, GameActivity.class);
        game.putExtra(GameActivity.EXTRA_GAME_DIR,
                new File(getFilesDir(), "game").getAbsolutePath());
        game.putExtra(GameActivity.EXTRA_LOG_PATH,
                new File(getFilesDir(), "nfsu2_log.txt").getAbsolutePath());
        game.putExtra(GameActivity.EXTRA_RENDER_SCALE, renderScale);
        game.putExtra(GameActivity.EXTRA_VSYNC, vsync.isChecked());
        startActivity(game);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null)
            refreshState();
    }
}
