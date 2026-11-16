package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.app.appearance.AppearanceManager;
import com.termux.app.appearance.AppearancePreviewView;

/**
 * A preference screen with a live preview pinned beside or above the list, so the preview stays in view
 * while you scroll to and drag a slider. The preview takes 30% of the height in portrait, and 40% of the
 * width on the left in landscape.
 *
 * It refreshes whenever any appearance setting is saved, which covers sliders, theme presets, imported
 * wallpapers and fonts, and edited colors.
 */
@Keep
public abstract class PreviewPreferenceFragment extends PreferenceFragmentCompat {

    /** Share of the screen the preview uses, out of 100, in portrait (height) and landscape (width). */
    private static final float PREVIEW_PORTRAIT_WEIGHT = 30f;
    private static final float PREVIEW_LANDSCAPE_WEIGHT = 40f;

    @Nullable
    private AppearancePreviewView mPreview;

    // Held in a field on purpose: SharedPreferences only keeps listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                if (mPreview != null) mPreview.refresh();
            }
        };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View list = super.onCreateView(inflater, container, savedInstanceState);

        final Context context = requireContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        final float radius = 12 * density;
        final int margin = Math.round(8 * density);

        AppearancePreviewView preview = new AppearancePreviewView(context);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        preview.setClipToOutline(true);
        mPreview = preview;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        LinearLayout.LayoutParams previewParams = landscape
            ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, PREVIEW_LANDSCAPE_WEIGHT)
            : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, PREVIEW_PORTRAIT_WEIGHT);
        previewParams.setMargins(margin, margin, margin, margin);

        LinearLayout.LayoutParams listParams = landscape
            ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f - PREVIEW_LANDSCAPE_WEIGHT)
            : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 100f - PREVIEW_PORTRAIT_WEIGHT);

        root.addView(preview, previewParams);
        root.addView(list, listParams);
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        AppearanceManager.getPrefs(requireContext()).registerOnSharedPreferenceChangeListener(mPrefsListener);
        // Settings may have changed while this screen was stopped, for example on the colors screen.
        if (mPreview != null) mPreview.refresh();
    }

    @Override
    public void onStop() {
        super.onStop();
        AppearanceManager.getPrefs(requireContext()).unregisterOnSharedPreferenceChangeListener(mPrefsListener);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mPreview = null;
    }
}
