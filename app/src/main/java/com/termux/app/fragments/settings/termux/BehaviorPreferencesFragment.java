package com.termux.app.fragments.settings.termux;

import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceFragmentCompat;

import com.termux.R;
import com.termux.app.appearance.AppearanceManager;

/** Settings for how Termux behaves, as opposed to how it looks. Values share the Appearance preferences file. */
@Keep
public class BehaviorPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(AppearanceManager.PREFS_NAME);
        setPreferencesFromResource(R.xml.termux_behavior_preferences, rootKey);
    }
}
