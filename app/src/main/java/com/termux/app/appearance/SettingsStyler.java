package com.termux.app.appearance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.shared.theme.ThemeUtils;

/**
 * Colors the Settings screens: the top bar, the status bar, and the accent color of switches and
 * sliders. By default it follows the terminal's own colors; it can also use a color picked with
 * the color wheel.
 */
public final class SettingsStyler {

    public static final String MODE_TERMINAL = "terminal";
    public static final String MODE_CUSTOM = "custom";

    public static final String DEFAULT_CUSTOM_COLOR = "#5c6bc0";

    /**
     * An earlier version also offered a red mode. Anyone who picked it is moved to the terminal theme
     * so the setting still shows a valid choice.
     */
    public static void normalizeMode(@NonNull Context context) {
        android.content.SharedPreferences prefs = AppearanceManager.getPrefs(context);
        String mode = prefs.getString(AppearanceManager.KEY_UI_MODE, MODE_TERMINAL);
        if (!MODE_TERMINAL.equals(mode) && !MODE_CUSTOM.equals(mode)) {
            prefs.edit().putString(AppearanceManager.KEY_UI_MODE, MODE_TERMINAL).apply();
        }
    }

    public static final class Style {
        public int toolbarColor;
        public int toolbarTextColor;
        public int statusBarColor;
        public int accent;
    }

    private SettingsStyler() {}

    @NonNull
    public static Style resolve(@NonNull Context context) {
        android.content.SharedPreferences prefs = AppearanceManager.getPrefs(context);
        String mode = prefs.getString(AppearanceManager.KEY_UI_MODE, MODE_TERMINAL);
        int pageBackground = ThemeUtils.getSystemAttrColor(context, android.R.attr.colorBackground, 0xFFFFFFFF);

        Style s = new Style();
        if (MODE_CUSTOM.equals(mode)) {
            Integer custom = ColorMath.parseHex(prefs.getString(AppearanceManager.KEY_UI_COLOR, DEFAULT_CUSTOM_COLOR));
            int base = custom != null ? custom : ColorMath.parseHex(DEFAULT_CUSTOM_COLOR);
            s.toolbarColor = base;
            s.statusBarColor = ColorMath.scale(base, 0.85f);
            s.toolbarTextColor = ColorMath.readableOn(base);
            s.accent = pickAccent(pageBackground, base);
        } else {
            int bg = parse(AppearanceManager.getEffectiveColorHex("background"), 0xFF000000);
            int fg = parse(AppearanceManager.getEffectiveColorHex("foreground"), 0xFFFFFFFF);
            s.toolbarColor = bg;
            s.statusBarColor = ColorMath.scale(bg, 0.85f);
            s.toolbarTextColor = ColorMath.contrast(fg, bg) >= 4.5 ? fg : ColorMath.readableOn(bg);
            int blue = parse(AppearanceManager.getEffectiveColorHex("color4"), 0xFF6495ED);
            s.accent = pickAccent(pageBackground, blue,
                parse(AppearanceManager.getEffectiveColorHex("color12"), blue),
                parse(AppearanceManager.getEffectiveColorHex("color2"), blue),
                parse(AppearanceManager.getEffectiveColorHex("color1"), blue),
                fg, bg);
        }
        return s;
    }

    /** Picks the first candidate that is readable on the page background. */
    private static int pickAccent(int pageBackground, int... candidates) {
        for (int c : candidates) {
            if (ColorMath.contrast(c, pageBackground) >= 2.0) return c | 0xFF000000;
        }
        // Nothing worked: black or white, whichever reads on this page.
        return ColorMath.readableOn(pageBackground);
    }

    private static int parse(String hex, int fallback) {
        Integer v = ColorMath.parseHex(hex);
        return v != null ? v : fallback;
    }

    /** Colors the activity's top bar and status bar. Call after the toolbar has been set up. */
    public static void apply(@NonNull final AppCompatActivity activity) {
        final Style s = resolve(activity);

        final Toolbar toolbar = activity.findViewById(com.termux.shared.R.id.toolbar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(s.toolbarColor);
            toolbar.setTitleTextColor(s.toolbarTextColor);
            toolbar.setSubtitleTextColor(s.toolbarTextColor);
            // The back arrow can be set a moment after onCreate, so tint it now and again once laid out.
            tintNavigationIcon(toolbar, s.toolbarTextColor);
            toolbar.post(new Runnable() {
                @Override
                public void run() {
                    tintNavigationIcon(toolbar, s.toolbarTextColor);
                }
            });
        }

        Window window = activity.getWindow();
        window.setStatusBarColor(s.statusBarColor);
        new WindowInsetsControllerCompat(window, window.getDecorView())
            .setAppearanceLightStatusBars(ColorMath.readableOn(s.statusBarColor) == 0xFF000000);
    }

    private static void tintNavigationIcon(Toolbar toolbar, int color) {
        Drawable icon = toolbar.getNavigationIcon();
        if (icon != null) {
            icon.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    /**
     * Tints switches and sliders on every preference screen shown in this activity, including
     * screens opened later. The accent is read once here; changing the style recreates the activity.
     */
    public static void installFragmentTinting(@NonNull AppCompatActivity activity) {
        final int accent = resolve(activity).accent;
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                  @NonNull View v, android.os.Bundle savedInstanceState) {
                    if (!(f instanceof PreferenceFragmentCompat)) return;
                    RecyclerView list = ((PreferenceFragmentCompat) f).getListView();
                    if (list == null) return;
                    list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                        @Override
                        public void onChildViewAttachedToWindow(@NonNull View view) {
                            tintTree(view, accent);
                        }

                        @Override
                        public void onChildViewDetachedFromWindow(@NonNull View view) {}
                    });
                    for (int i = 0; i < list.getChildCount(); i++) tintTree(list.getChildAt(i), accent);
                }
            }, true);
    }

    public static void tintTree(@NonNull View view, int accent) {
        if (view instanceof SwitchCompat) {
            SwitchCompat sw = (SwitchCompat) view;
            int[][] states = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFBDBDBD}));
            sw.setTrackTintList(new ColorStateList(states, new int[]{accent, 0xFF9E9E9E}));
        } else if (view instanceof SeekBar) {
            SeekBar bar = (SeekBar) view;
            ColorStateList tint = ColorStateList.valueOf(accent);
            bar.setProgressTintList(tint);
            bar.setThumbTintList(tint);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintTree(group.getChildAt(i), accent);
        }
    }
}
