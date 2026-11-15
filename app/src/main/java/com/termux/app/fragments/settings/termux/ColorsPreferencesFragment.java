package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.termux.R;
import com.termux.app.appearance.AppearanceManager;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

import java.util.Map;
import java.util.TreeMap;

/**
 * Edits foreground, background, cursor and the 16 ANSI colors. Changes are written straight to
 * {@code ~/.termux/colors.properties}, on top of whatever theme preset is currently installed.
 */
@Keep
public class ColorsPreferencesFragment extends PreferenceFragmentCompat {

    /** Colors as stored in colors.properties; only keys the user or a preset set are present. */
    private final Map<String, String> mColors = new TreeMap<>();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        build(screen);
    }

    private void build(@NonNull PreferenceScreen screen) {
        final Context context = requireContext();

        mColors.clear();
        for (Map.Entry<Object, Object> e : AppearanceManager.readColorProperties().entrySet()) {
            mColors.put((String) e.getKey(), (String) e.getValue());
        }

        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(R.string.termux_appearance_colors_header);
        screen.addPreference(category);

        for (final String key : AppearanceManager.getColorKeys()) {
            final EditTextPreference pref = new EditTextPreference(context);
            pref.setKey("color_edit_" + key);
            pref.setPersistent(false);
            pref.setTitle(key);
            pref.setDialogTitle(key);

            String hex = currentHex(key);
            pref.setText(hex);
            pref.setSummary(hex);
            pref.setIcon(swatch(context, hex));
            pref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    String value = newValue == null ? "" : ((String) newValue).trim();
                    if (!value.startsWith("#")) value = "#" + value;
                    if (!AppearanceManager.isValidHexColor(value)) {
                        Toast.makeText(context, R.string.termux_appearance_colors_invalid, Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    value = value.toLowerCase(java.util.Locale.ROOT);

                    mColors.put(key, value);
                    if (!AppearanceManager.writeColorProperties(context, mColors)) return false;

                    pref.setText(value);
                    pref.setSummary(value);
                    pref.setIcon(swatch(context, value));
                    // We stored the normalised value ourselves, so do not let the preference store the raw input.
                    return false;
                }
            });
            category.addPreference(pref);
        }

        Preference reset = new Preference(context);
        reset.setKey("colors_reset");
        reset.setPersistent(false);
        reset.setTitle(R.string.termux_appearance_colors_reset_title);
        reset.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if (AppearanceManager.applyPreset(context, AppearanceManager.PRESET_DEFAULT)) {
                    PreferenceScreen s = getPreferenceScreen();
                    s.removeAll();
                    build(s);
                }
                return true;
            }
        });
        screen.addPreference(reset);
    }

    /** The color as it is set in the colors file, falling back to the app's current default for that slot. */
    @NonNull
    private String currentHex(@NonNull String key) {
        String fromFile = mColors.get(key);
        if (AppearanceManager.isValidHexColor(fromFile)) return fromFile.toLowerCase(java.util.Locale.ROOT);

        int index;
        if (key.equals("foreground")) index = TextStyle.COLOR_INDEX_FOREGROUND;
        else if (key.equals("background")) index = TextStyle.COLOR_INDEX_BACKGROUND;
        else if (key.equals("cursor")) index = TextStyle.COLOR_INDEX_CURSOR;
        else index = Integer.parseInt(key.substring("color".length()));

        int argb = TerminalColors.COLOR_SCHEME.mDefaultColors[index];
        return String.format("#%06x", argb & 0xFFFFFF);
    }

    private static GradientDrawable swatch(Context context, String hex) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(28 * density);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.parseColor(hex));
        d.setStroke(Math.max(1, Math.round(density)), 0x66888888);
        d.setSize(size, size);
        return d;
    }
}
