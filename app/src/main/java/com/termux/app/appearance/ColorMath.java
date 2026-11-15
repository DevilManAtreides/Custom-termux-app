package com.termux.app.appearance;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Small, Android-free color helpers (HSV conversion, hex parsing, contrast). Kept free of
 * android.* classes so the logic can be unit tested on a plain JVM.
 */
public final class ColorMath {

    private ColorMath() {}

    /** Converts 0xRRGGBB (alpha ignored) to {hue 0..360, saturation 0..1, value 0..1}. */
    @NonNull
    public static float[] rgbToHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float h;
        if (delta == 0f) {
            h = 0f;
        } else if (max == r) {
            h = 60f * (((g - b) / delta) % 6f);
        } else if (max == g) {
            h = 60f * (((b - r) / delta) + 2f);
        } else {
            h = 60f * (((r - g) / delta) + 4f);
        }
        if (h < 0f) h += 360f;

        float s = max == 0f ? 0f : delta / max;
        return new float[]{h, s, max};
    }

    /** Converts hue 0..360, saturation 0..1, value 0..1 to an opaque 0xFFRRGGBB color. */
    public static int hsvToRgb(float h, float s, float v) {
        h = ((h % 360f) + 360f) % 360f;
        s = clamp01(s);
        v = clamp01(v);

        float c = v * s;
        float x = c * (1f - Math.abs(((h / 60f) % 2f) - 1f));
        float m = v - c;

        float r, g, b;
        if (h < 60f)       { r = c; g = x; b = 0; }
        else if (h < 120f) { r = x; g = c; b = 0; }
        else if (h < 180f) { r = 0; g = c; b = x; }
        else if (h < 240f) { r = 0; g = x; b = c; }
        else if (h < 300f) { r = x; g = 0; b = c; }
        else               { r = c; g = 0; b = x; }

        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    /** Formats as lowercase {@code #rrggbb}. */
    @NonNull
    public static String toHex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    /** True for {@code #rrggbb} or {@code rrggbb}. */
    public static boolean isValidHex(@Nullable String text) {
        return parseHex(text) != null;
    }

    /** Parses {@code #rrggbb} or {@code rrggbb} to an opaque 0xFFRRGGBB color, or null if invalid. */
    @Nullable
    public static Integer parseHex(@Nullable String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(t.charAt(i), 16) < 0) return null;
        }
        return 0xFF000000 | Integer.parseInt(t, 16);
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    public static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
    }

    /** WCAG contrast ratio, 1 (identical) to 21 (black on white). */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** Black or white, whichever is easier to read on {@code background}. */
    public static int readableOn(int background) {
        return contrast(0xFFFFFFFF, background) >= contrast(0xFF000000, background) ? 0xFFFFFFFF : 0xFF000000;
    }

    /** Multiplies each channel by {@code factor} (below 1 darkens). Result is opaque. */
    public static int scale(int rgb, float factor) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * factor));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * factor));
        int b = Math.min(255, Math.round((rgb & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
