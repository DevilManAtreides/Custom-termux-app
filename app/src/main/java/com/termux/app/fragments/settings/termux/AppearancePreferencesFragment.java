package com.termux.app.fragments.settings.termux;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.appearance.AppearanceManager;

import java.util.ArrayList;
import java.util.Locale;

/**
 * GUI for wallpaper, blur, fonts and color themes. All values live in
 * {@link AppearanceManager#PREFS_NAME}; the terminal activity picks changes up when it returns
 * to the foreground.
 */
@Keep
public class AppearancePreferencesFragment extends PreferenceFragmentCompat {

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> mWallpaperPicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), new androidx.activity.result.ActivityResultCallback<Uri>() {
            @Override
            public void onActivityResult(@Nullable Uri uri) {
                if (uri != null) importWallpaper(uri);
            }
        });

    private final ActivityResultLauncher<String> mFontPicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), new androidx.activity.result.ActivityResultCallback<Uri>() {
            @Override
            public void onActivityResult(@Nullable Uri uri) {
                if (uri != null) importFont(uri);
            }
        });

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(AppearanceManager.PREFS_NAME);
        setPreferencesFromResource(R.xml.termux_appearance_preferences, rootKey);

        setUpWallpaper();
        setUpFont();
        setUpTheme();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The custom colors screen may have changed the theme while we were in the background.
        refreshThemeSummary();
        refreshWallpaperSummary();
        refreshFontSummary();
    }

    // ---------------------------------------------------------------- wallpaper

    private void setUpWallpaper() {
        final SwitchPreferenceCompat enabled = findPreference(AppearanceManager.KEY_WALLPAPER_ENABLED);
        if (enabled != null) {
            enabled.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if ((Boolean) newValue && !AppearanceManager.hasWallpaper(requireContext())) {
                        // Nothing to show yet: ask for an image first, which turns the switch on once imported.
                        mWallpaperPicker.launch("image/*");
                        return false;
                    }
                    AppearanceManager.markStyleChanged(requireContext());
                    return true;
                }
            });
        }

        Preference.OnPreferenceChangeListener bump = new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                AppearanceManager.markStyleChanged(requireContext());
                return true;
            }
        };
        Preference blur = findPreference(AppearanceManager.KEY_WALLPAPER_BLUR);
        if (blur != null) blur.setOnPreferenceChangeListener(bump);
        Preference dim = findPreference(AppearanceManager.KEY_WALLPAPER_DIM);
        if (dim != null) dim.setOnPreferenceChangeListener(bump);

        Preference pick = findPreference("wallpaper_pick");
        if (pick != null) {
            pick.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    mWallpaperPicker.launch("image/*");
                    return true;
                }
            });
        }

        Preference remove = findPreference("wallpaper_remove");
        if (remove != null) {
            remove.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    AppearanceManager.removeWallpaper(requireContext());
                    SwitchPreferenceCompat sw = findPreference(AppearanceManager.KEY_WALLPAPER_ENABLED);
                    if (sw != null) sw.setChecked(false);
                    refreshWallpaperSummary();
                    return true;
                }
            });
        }
        refreshWallpaperSummary();
    }

    private void refreshWallpaperSummary() {
        if (getContext() == null) return;
        boolean has = AppearanceManager.hasWallpaper(requireContext());
        Preference pick = findPreference("wallpaper_pick");
        if (pick != null) {
            pick.setSummary(has ? R.string.termux_appearance_wallpaper_pick_summary_set
                : R.string.termux_appearance_wallpaper_pick_summary_none);
        }
        Preference remove = findPreference("wallpaper_remove");
        if (remove != null) remove.setEnabled(has);
        Preference blur = findPreference(AppearanceManager.KEY_WALLPAPER_BLUR);
        if (blur != null) blur.setEnabled(has);
        Preference dim = findPreference(AppearanceManager.KEY_WALLPAPER_DIM);
        if (dim != null) dim.setEnabled(has);
    }

    private void importWallpaper(final Uri uri) {
        final android.content.Context app = requireContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = AppearanceManager.importWallpaper(app, uri);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        if (ok) {
                            SwitchPreferenceCompat sw = findPreference(AppearanceManager.KEY_WALLPAPER_ENABLED);
                            if (sw != null) sw.setChecked(true);
                            AppearanceManager.markStyleChanged(app);
                        } else {
                            Toast.makeText(app, R.string.termux_appearance_wallpaper_import_failed, Toast.LENGTH_SHORT).show();
                        }
                        refreshWallpaperSummary();
                    }
                });
            }
        }, "appearance-wallpaper-import").start();
    }

    // ---------------------------------------------------------------- font

    private void setUpFont() {
        Preference pick = findPreference("font_pick");
        if (pick != null) {
            pick.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    // Font files have no reliable MIME type across file providers, so accept anything and validate the header.
                    mFontPicker.launch("*/*");
                    return true;
                }
            });
        }
        Preference reset = findPreference("font_reset");
        if (reset != null) {
            reset.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    AppearanceManager.resetFont(requireContext());
                    refreshFontSummary();
                    return true;
                }
            });
        }
        refreshFontSummary();
    }

    private void refreshFontSummary() {
        boolean custom = AppearanceManager.hasCustomFont();
        Preference pick = findPreference("font_pick");
        if (pick != null) {
            pick.setSummary(custom ? R.string.termux_appearance_font_pick_summary_custom
                : R.string.termux_appearance_font_pick_summary_default);
        }
        Preference reset = findPreference("font_reset");
        if (reset != null) reset.setEnabled(custom);
    }

    private void importFont(final Uri uri) {
        final android.content.Context app = requireContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = AppearanceManager.importFont(app, uri);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) return;
                        if (!ok) {
                            Toast.makeText(app, R.string.termux_appearance_font_import_failed, Toast.LENGTH_SHORT).show();
                        }
                        refreshFontSummary();
                    }
                });
            }
        }, "appearance-font-import").start();
    }

    // ---------------------------------------------------------------- theme

    private void setUpTheme() {
        final ListPreference preset = findPreference(AppearanceManager.KEY_THEME_PRESET);
        if (preset == null) return;

        ArrayList<String> names = AppearanceManager.listPresets(requireContext());
        ArrayList<CharSequence> entries = new ArrayList<>();
        ArrayList<CharSequence> values = new ArrayList<>();
        entries.add(getString(R.string.termux_appearance_theme_default));
        values.add(AppearanceManager.PRESET_DEFAULT);
        for (String name : names) {
            entries.add(prettify(name));
            values.add(name);
        }
        preset.setEntries(entries.toArray(new CharSequence[0]));
        preset.setEntryValues(values.toArray(new CharSequence[0]));

        preset.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                boolean ok = AppearanceManager.applyPreset(requireContext(), (String) newValue);
                if (ok) setThemeSummary((String) newValue);
                return ok;
            }
        });
        refreshThemeSummary();
    }

    private void refreshThemeSummary() {
        if (getContext() == null) return;
        String value = AppearanceManager.getPrefs(requireContext())
            .getString(AppearanceManager.KEY_THEME_PRESET, AppearanceManager.PRESET_DEFAULT);
        setThemeSummary(value);
    }

    private void setThemeSummary(@Nullable String value) {
        ListPreference preset = findPreference(AppearanceManager.KEY_THEME_PRESET);
        if (preset == null) return;
        if ("custom".equals(value)) {
            preset.setSummary(R.string.termux_appearance_theme_custom);
        } else if (value == null || AppearanceManager.PRESET_DEFAULT.equals(value)) {
            preset.setSummary(R.string.termux_appearance_theme_default);
        } else {
            preset.setSummary(prettify(value));
        }
    }

    /** "tokyo_night" -> "Tokyo Night". */
    private static String prettify(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("[_-]")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return sb.toString();
    }
}
