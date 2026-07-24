package com.github.xfalcon.vhosts;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.*;
import com.github.xfalcon.vhosts.util.LogUtils;
import org.xbill.DNS.Address;

public class SettingsFragment extends PreferenceFragmentCompat implements
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static String TAG = SettingsFragment.class.getName();

    public static final String PREFS_NAME = SettingsFragment.class.getName();
    public static final String IPV4_DNS = "IPV4_DNS";
    public static final String IS_CUS_DNS = "IS_CUS_DNS";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        final SharedPreferences sharedPreferences = getPreferenceScreen().getSharedPreferences();
        PreferenceScreen prefScreen = getPreferenceScreen();
        handleSummary(prefScreen, sharedPreferences);

        Preference dnsCustomPref = findPreference(IPV4_DNS);
        dnsCustomPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                String ipv4_dns = (String)newValue;
                try {
                    Address.getByAddress(ipv4_dns);
                    return true;
                } catch (Exception e) {
                    LogUtils.e(TAG, e.getMessage(), e);
                    android.widget.Toast.makeText(preference.getContext(), getString(R.string.dns4_error), android.widget.Toast.LENGTH_LONG).show();
                }
                return false;
            }
        });
    }

    private void handleSummary(PreferenceGroup preferenceGroup, SharedPreferences sharedPreferences) {
        int count = preferenceGroup.getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference p = preferenceGroup.getPreference(i);
            if (p instanceof PreferenceCategory) {
                handleSummary((PreferenceCategory) p, sharedPreferences);
            }
            if (!(p instanceof CheckBoxPreference)) {
                String value = sharedPreferences.getString(p.getKey(), "");
                setPreferenceSummary(p, value);
            }
        }
    }

    private void setPreferenceSummary(Preference preference, String value) {
        if (preference instanceof ListPreference) {
            ListPreference listPreference = (ListPreference) preference;
            int prefIndex = listPreference.findIndexOfValue(value);
            if (prefIndex >= 0) {
                listPreference.setSummary(listPreference.getEntries()[prefIndex]);
            }
        } else if (preference instanceof EditTextPreference) {
            preference.setSummary(value);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Preference preference = findPreference(key);
        if (null != preference) {
            if (!(preference instanceof CheckBoxPreference)) {
                String value = sharedPreferences.getString(preference.getKey(), "");
                setPreferenceSummary(preference, value);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public android.view.View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container, Bundle savedInstanceState) {
        inflater.getContext().setTheme(R.style.AppPreferenceSettingsFragmentTheme);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(android.view.View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 让 preference 列表透明，露出 activity 的 colorSurface，与主页背景保持一致
        if (getListView() != null) {
            getListView().setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }
}
