package com.termux.app.appearance;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central place for the wallpaper / font / color customisation that is edited from the
 * Appearance settings screen and rendered by {@link com.termux.app.TermuxActivity}.
 *
 * Fonts and colors are written to the same files the stock app already reads
 * ({@link TermuxConstants#TERMUX_FONT_FILE} and {@link TermuxConstants#TERMUX_COLOR_PROPERTIES_FILE}),
 * so the GUI and hand edited files / {@code termux-reload-settings} keep working together.
 */
public final class AppearanceManager {

    private static final String LOG_TAG = "AppearanceManager";

    public static final String PREFS_NAME = "termux_appearance";

    public static final String KEY_WALLPAPER_ENABLED = "wallpaper_enabled";
    public static final String KEY_WALLPAPER_BLUR = "wallpaper_blur";
    public static final String KEY_WALLPAPER_DIM = "wallpaper_dim";
    public static final String KEY_THEME_PRESET = "theme_preset";
    /** Bumped whenever anything changes, so the terminal activity knows to re-apply on return. */
    public static final String KEY_STYLE_VERSION = "style_version";

    public static final int MAX_BLUR = 40;
    public static final int MAX_DIM = 90;

    public static final String PRESET_DEFAULT = "default";

    private static final String WALLPAPER_FILE_NAME = "terminal_wallpaper.jpg";
    private static final int WALLPAPER_MAX_DIMENSION = 1600;
    /** Blur runs on a copy scaled to this size; blurring a small image is faster and looks the same once scaled up. */
    private static final int BLUR_WORKING_DIMENSION = 720;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    /** Identifies what is currently shown, so unchanged settings do not trigger a re-blur. */
    private static String sAppliedKey;
    private static int sApplyGeneration;

    private AppearanceManager() {}

    public static SharedPreferences getPrefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static long getStyleVersion(@NonNull Context context) {
        return getPrefs(context).getLong(KEY_STYLE_VERSION, 0);
    }

    public static void markStyleChanged(@NonNull Context context) {
        SharedPreferences prefs = getPrefs(context);
        prefs.edit().putLong(KEY_STYLE_VERSION, prefs.getLong(KEY_STYLE_VERSION, 0) + 1).apply();
    }

    // ---------------------------------------------------------------- wallpaper

    @NonNull
    public static File getWallpaperFile(@NonNull Context context) {
        return new File(context.getApplicationContext().getDir("appearance", Context.MODE_PRIVATE), WALLPAPER_FILE_NAME);
    }

    public static boolean hasWallpaper(@NonNull Context context) {
        File f = getWallpaperFile(context);
        return f.isFile() && f.length() > 0;
    }

    public static boolean isWallpaperEnabled(@NonNull Context context) {
        return getPrefs(context).getBoolean(KEY_WALLPAPER_ENABLED, false) && hasWallpaper(context);
    }

    /**
     * Copies the picked image into app storage, downscaled and rotated according to its EXIF
     * orientation. Must be called off the main thread.
     */
    public static boolean importWallpaper(@NonNull Context context, @NonNull Uri uri) {
        Context app = context.getApplicationContext();
        File tmp = new File(app.getCacheDir(), "wallpaper_import.tmp");
        try {
            try (InputStream in = app.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return false;
                copy(in, out);
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WALLPAPER_MAX_DIMENSION) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap decoded = BitmapFactory.decodeFile(tmp.getAbsolutePath(), opts);
            if (decoded == null) return false;

            int rotation = 0;
            try {
                int orientation = new ExifInterface(tmp.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            } catch (IOException ignored) {
                // No usable EXIF data, keep the image as decoded.
            }
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
                if (rotated != decoded) decoded.recycle();
                decoded = rotated;
            }

            try (OutputStream out = new FileOutputStream(getWallpaperFile(app))) {
                decoded.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            decoded.recycle();
            markStyleChanged(app);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import wallpaper", e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    public static void removeWallpaper(@NonNull Context context) {
        //noinspection ResultOfMethodCallIgnored
        getWallpaperFile(context).delete();
        getPrefs(context).edit().putBoolean(KEY_WALLPAPER_ENABLED, false).apply();
        markStyleChanged(context);
    }

    /**
     * Shows (or hides) the wallpaper in {@code wallpaperView} using the saved blur and dim settings,
     * and makes the extra keys bar translucent so the wallpaper is visible behind it.
     * Safe to call repeatedly: unchanged settings are a no-op.
     */
    public static void applyWallpaper(@NonNull final Activity activity, @Nullable final ImageView wallpaperView) {
        if (wallpaperView == null) return;

        final Context app = activity.getApplicationContext();
        final View toolbar = activity.findViewById(R.id.terminal_toolbar_view_pager);
        final boolean enabled = isWallpaperEnabled(app);

        if (toolbar != null) {
            toolbar.setBackgroundColor(enabled ? 0x99000000 : Color.BLACK);
        }

        if (!enabled) {
            sAppliedKey = null;
            sApplyGeneration++;
            wallpaperView.setImageDrawable(null);
            wallpaperView.setVisibility(View.GONE);
            return;
        }

        SharedPreferences prefs = getPrefs(app);
        final int blur = clamp(prefs.getInt(KEY_WALLPAPER_BLUR, 0), 0, MAX_BLUR);
        final int dim = clamp(prefs.getInt(KEY_WALLPAPER_DIM, 30), 0, MAX_DIM);
        final File file = getWallpaperFile(app);
        final String key = file.lastModified() + "|" + blur;

        // Dim is just a color filter, so it never needs a re-blur.
        wallpaperView.setColorFilter(Color.argb(Math.round(dim * 255f / 100f), 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperView.setVisibility(View.VISIBLE);

        if (key.equals(sAppliedKey) && wallpaperView.getDrawable() != null) return;

        final int generation = ++sApplyGeneration;
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap result = null;
                try {
                    Bitmap src = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (src != null) {
                        result = blur > 0 ? blurScaled(src, blur) : src;
                        if (result != src) src.recycle();
                    }
                } catch (Throwable t) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to prepare wallpaper", t instanceof Exception ? (Exception) t : new Exception(t));
                }
                final Bitmap finalResult = result;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != sApplyGeneration || activity.isFinishing() || finalResult == null) return;
                        wallpaperView.setImageDrawable(new BitmapDrawable(activity.getResources(), finalResult));
                        sAppliedKey = key;
                    }
                });
            }
        });
    }

    private static Bitmap blurScaled(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        float scale = Math.min(1f, BLUR_WORKING_DIMENSION / (float) Math.max(w, h));
        Bitmap small = scale < 1f
            ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true)
            : src;
        Bitmap blurred = BlurUtils.stackBlur(small, radius);
        if (small != src) small.recycle();
        return blurred;
    }

    // ---------------------------------------------------------------- fonts

    public static boolean hasCustomFont() {
        File f = TermuxConstants.TERMUX_FONT_FILE;
        return f.isFile() && f.length() > 0;
    }

    /** Validates and installs a font as {@code ~/.termux/font.ttf}. Must be called off the main thread. */
    public static boolean importFont(@NonNull Context context, @NonNull Uri uri) {
        Context app = context.getApplicationContext();
        File tmp = new File(app.getCacheDir(), "font_import.tmp");
        try {
            try (InputStream in = app.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return false;
                copy(in, out);
            }
            if (!looksLikeFont(tmp)) return false;
            // Throws or yields the default font if the file is unusable on this device.
            Typeface.createFromFile(tmp);

            File target = TermuxConstants.TERMUX_FONT_FILE;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
            markStyleChanged(app);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import font", e);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    public static void resetFont(@NonNull Context context) {
        //noinspection ResultOfMethodCallIgnored
        TermuxConstants.TERMUX_FONT_FILE.delete();
        markStyleChanged(context);
    }

    /** TrueType, OpenType/CFF, TrueType collection or legacy Apple 'true' header. */
    private static boolean looksLikeFont(File f) throws IOException {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(magic) != 4) return false;
        }
        String tag = new String(magic, StandardCharsets.ISO_8859_1);
        return (magic[0] == 0 && magic[1] == 1 && magic[2] == 0 && magic[3] == 0)
            || tag.equals("OTTO") || tag.equals("ttcf") || tag.equals("true");
    }

    // ---------------------------------------------------------------- colors / themes

    /** Names in the order they are shown in the color editor. */
    public static ArrayList<String> getColorKeys() {
        ArrayList<String> keys = new ArrayList<>();
        keys.add("foreground");
        keys.add("background");
        keys.add("cursor");
        for (int i = 0; i < 16; i++) keys.add("color" + i);
        return keys;
    }

    /** Lists theme preset asset names (without extension), sorted. */
    public static ArrayList<String> listPresets(@NonNull Context context) {
        ArrayList<String> names = new ArrayList<>();
        try {
            String[] files = context.getAssets().list("themes");
            if (files != null) {
                for (String f : files) {
                    if (f.endsWith(".properties")) names.add(f.substring(0, f.length() - ".properties".length()));
                }
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to list theme presets", e);
        }
        Collections.sort(names);
        return names;
    }

    /** Installs a bundled preset as {@code ~/.termux/colors.properties}; {@link #PRESET_DEFAULT} removes the file. */
    public static boolean applyPreset(@NonNull Context context, @NonNull String preset) {
        Context app = context.getApplicationContext();
        File target = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
        try {
            if (PRESET_DEFAULT.equals(preset)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            } else {
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                try (InputStream in = app.getAssets().open("themes/" + preset + ".properties");
                     OutputStream out = new FileOutputStream(target)) {
                    copy(in, out);
                }
            }
            getPrefs(app).edit().putString(KEY_THEME_PRESET, preset).apply();
            markStyleChanged(app);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply preset " + preset, e);
            return false;
        }
    }

    /** Reads the user's current colors file (empty if there is none or it is unreadable). */
    @NonNull
    public static Properties readColorProperties() {
        Properties props = new Properties();
        File f = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                props.load(in);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read colors.properties", e);
            }
        }
        return props;
    }

    /**
     * Writes colors as plain {@code key=#rrggbb} lines. {@link Properties#store} is avoided on purpose
     * because it escapes '#' and adds a timestamp, which makes the file ugly to hand edit afterwards.
     */
    public static boolean writeColorProperties(@NonNull Context context, @NonNull Map<String, String> colors) {
        File target = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : new TreeMap<>(colors).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            getPrefs(context).edit().putString(KEY_THEME_PRESET, "custom").apply();
            markStyleChanged(context);
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write colors.properties", e);
            return false;
        }
    }

    public static boolean isValidHexColor(@Nullable String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}");
    }

    // ---------------------------------------------------------------- helpers

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
