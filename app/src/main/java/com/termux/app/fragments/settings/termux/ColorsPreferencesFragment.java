package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.termux.R;
import com.termux.app.appearance.AppearanceManager;
import com.termux.app.appearance.ColorMath;
import com.termux.app.appearance.ColorPickerDialog;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * Edits foreground, background, cursor and the 16 ANSI colors with a color wheel. Changes are
 * written straight to {@code ~/.termux/colors.properties}, on top of whatever theme preset is
 * currently installed.
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

        ArrayList<String> keys = AppearanceManager.getColorKeys();
        final String[] descriptions = getResources().getStringArray(R.array.termux_appearance_color_descriptions);

        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            // The title is the plain key name (foreground, background, cursor, color0 ... color15),
            // exactly as it appears in colors.properties. Never a hue, since the user sets that.
            final String title = key;
            final String description = i < descriptions.length ? descriptions[i] : "";

            final Preference pref = new Preference(context);
            pref.setKey("color_edit_" + key);
            pref.setPersistent(false);
            pref.setTitle(title);
            bind(context, pref, description, currentHex(key));

            pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    ColorPickerDialog.show(context, title, description, parse(currentHex(key)),
                        new ColorPickerDialog.Callback() {
                            @Override
                            public void onColorSelected(int rgb) {
                                String value = ColorMath.toHex(rgb);
                                mColors.put(key, value);
                                if (!AppearanceManager.writeColorProperties(context, mColors)) {
                                    Toast.makeText(context, R.string.termux_appearance_colors_save_failed, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                bind(context, pref, description, value);
                            }
                        });
                    return true;
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

    /** Shows the description with the current hex value under the title, and a swatch as the icon. */
    private static void bind(Context context, Preference pref, String description, String hex) {
        pref.setSummary(description + "\n" + hex);
        pref.setIcon(swatch(context, hex));
    }

    /** The color as it is set in the colors file, falling back to the app's current default for that slot. */
    @NonNull
    private String currentHex(@NonNull String key) {
        Integer fromFile = ColorMath.parseHex(mColors.get(key));
        return fromFile != null ? ColorMath.toHex(fromFile) : AppearanceManager.defaultColorHex(key);
    }

    private static int parse(String hex) {
        Integer v = ColorMath.parseHex(hex);
        return v != null ? v : 0xFF000000;
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
