/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.deviceinfo.aboutphone;

import static androidx.core.content.ContextCompat.getMainExecutor;

import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.app.settings.SettingsEnums;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settings.R;
import com.android.settings.core.SubSettingLauncher;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.deviceinfo.BluetoothAddressPreferenceController;
import com.android.settings.deviceinfo.BuildNumberPreferenceController;
import com.android.settings.deviceinfo.FccEquipmentIdPreferenceController;
import com.android.settings.deviceinfo.StorageDashboardFragment;
import com.android.settings.deviceinfo.firmwareversion.FirmwareVersionSettings;
import com.android.settings.deviceinfo.FeedbackPreferenceController;
import com.android.settings.deviceinfo.IpAddressPreferenceController;
import com.android.settings.deviceinfo.ManualPreferenceController;
import com.android.settings.deviceinfo.UptimePreferenceController;
import com.android.settings.deviceinfo.WifiMacAddressPreferenceController;
import com.android.settings.deviceinfo.imei.ImeiInfoPreferenceController;
import com.android.settings.deviceinfo.simstatus.EidStatus;
import com.android.settings.deviceinfo.simstatus.SimEidPreferenceController;
import com.android.settings.deviceinfo.simstatus.SimStatusPreferenceController;
import com.android.settings.deviceinfo.simstatus.SlotSimStatus;
import com.android.settings.flags.Flags;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.wifi.tether.WifiDeviceNameTextValidator;
import com.android.settingslib.deviceinfo.PrivateStorageInfo;
import com.android.settingslib.deviceinfo.StorageManagerVolumeProvider;
import com.android.settingslib.fuelgauge.BatteryUtils;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.utils.ThreadUtils;
import com.android.settingslib.widget.LayoutPreference;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// LINT.IfChange
@SearchIndexable
public class MyDeviceInfoFragment extends DashboardFragment {

    private static final String LOG_TAG = "MyDeviceInfoFragment";
    private static final String KEY_EID_INFO = "eid_info";
    private static final String KEY_MY_DEVICE_INFO_HEADER = "my_device_info_header";
    private static final String KEY_DEVICE_DETAIL_CATEGORY = "device_detail_category";
    private static final String KEY_DEVICE_IDENTIFIERS_CATEGORY = "device_identifiers_category";
    private static final String KEY_OTHERS_TOGGLE = "others_section_toggle";

    private BuildNumberPreferenceController mBuildNumberPreferenceController;

    private ImageView mBannerWallpaperView;
    private TextView mDeviceNameValueView;
    private View mDeviceNameCard;
    private StorageUsageView mStorageWave;
    private TextView mStorageValue;
    private BroadcastReceiver mWallpaperChangedReceiver;
    private ContentObserver mDeviceNameObserver;
    private String mPendingDeviceName;
    private boolean mExtraSectionsShown;

    private final RecyclerView.AdapterDataObserver mVisibilityEnforcer =
            new RecyclerView.AdapterDataObserver() {
                @Override
                public void onChanged() {
                    applyExtraSectionsVisibility();
                }

                @Override
                public void onItemRangeChanged(int positionStart, int itemCount) {
                    applyExtraSectionsVisibility();
                }

                @Override
                public void onItemRangeInserted(int positionStart, int itemCount) {
                    applyExtraSectionsVisibility();
                }

                @Override
                public void onItemRangeRemoved(int positionStart, int itemCount) {
                    applyExtraSectionsVisibility();
                }

                @Override
                public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                    applyExtraSectionsVisibility();
                }
            };

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.DEVICEINFO;
    }

    @Override
    public int getHelpResource() {
        return R.string.help_uri_about;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mBuildNumberPreferenceController = use(BuildNumberPreferenceController.class);
        mBuildNumberPreferenceController.setHost(this /* parent */);
    }

    @Override
    protected @NonNull Set<String> getPreferenceKeysInHierarchy() {
        Set<String> keys = super.getPreferenceKeysInHierarchy();
        // add async preference key manually
        keys.add(KEY_EID_INFO);
        return keys;
    }

    @Override
    protected void onPreferenceScreenCreatedFromResource(
            @NonNull PreferenceScreen preferenceScreen) {
        if (isCatalystEnabled()) {
            // remove the preference created from resource to avoid duplicated key
            preferenceScreen.removePreferenceRecursively(KEY_EID_INFO);
        }
        final Preference othersToggle = preferenceScreen.findPreference(KEY_OTHERS_TOGGLE);
        if (othersToggle != null) {
            othersToggle.setOnPreferenceClickListener(preference -> {
                toggleExtraSections();
                return true;
            });
        }
    }

    private void toggleExtraSections() {
        mExtraSectionsShown = !mExtraSectionsShown;
        applyExtraSectionsVisibility();
    }

    private void applyExtraSectionsVisibility() {
        final PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        final Preference details = screen.findPreference(KEY_DEVICE_DETAIL_CATEGORY);
        final Preference identifiers = screen.findPreference(KEY_DEVICE_IDENTIFIERS_CATEGORY);
        final Preference toggle = screen.findPreference(KEY_OTHERS_TOGGLE);
        // Guard each write so our own enforcement doesn't re-trigger the adapter observer.
        if (details != null && details.isVisible() != mExtraSectionsShown) {
            details.setVisible(mExtraSectionsShown);
        }
        if (identifiers != null && identifiers.isVisible() != mExtraSectionsShown) {
            identifiers.setVisible(mExtraSectionsShown);
        }
        if (toggle != null) {
            final CharSequence summary = getString(mExtraSectionsShown
                    ? R.string.my_device_info_others_summary_hide
                    : R.string.my_device_info_others_summary_show);
            if (!TextUtils.equals(toggle.getSummary(), summary)) {
                toggle.setSummary(summary);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        initHeader();
        registerHeaderObservers();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Enforce the collapsed state: something re-shows the detail category on
        // initial load, ignoring the XML flag. Re-applying here wins over it.
        applyExtraSectionsVisibility();
        final RecyclerView listView = getListView();
        if (listView != null && listView.getAdapter() != null) {
            try {
                listView.getAdapter().unregisterAdapterDataObserver(mVisibilityEnforcer);
            } catch (IllegalStateException ignored) {
                // Not registered yet.
            }
            listView.getAdapter().registerAdapterDataObserver(mVisibilityEnforcer);
        }
    }

    @Override
    public void onPause() {
        final RecyclerView listView = getListView();
        if (listView != null && listView.getAdapter() != null) {
            try {
                listView.getAdapter().unregisterAdapterDataObserver(mVisibilityEnforcer);
            } catch (IllegalStateException ignored) {
                // Already unregistered.
            }
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        unregisterHeaderObservers();
        super.onStop();
    }

    @Override
    protected String getLogTag() {
        return LOG_TAG;
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.my_device_info;
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        return buildPreferenceControllers(context, this /* fragment */, getSettingsLifecycle());
    }

    private static List<AbstractPreferenceController> buildPreferenceControllers(
            Context context, MyDeviceInfoFragment fragment, Lifecycle lifecycle) {
        // disable catalyst for settings search (i.e. fragment is null)
        boolean isCatalystEnabled = Flags.catalystMyDeviceInfoPrefScreen() && fragment != null;
        final List<AbstractPreferenceController> controllers = new ArrayList<>();

        final Executor executor = (fragment == null) ? getMainExecutor(context) :
                Executors.newSingleThreadExecutor();
        androidx.lifecycle.Lifecycle lifecycleObject = (fragment == null) ? null :
                fragment.getLifecycle();
        final SlotSimStatus slotSimStatus = new SlotSimStatus(context, executor, lifecycleObject);

        controllers.add(new IpAddressPreferenceController(context, lifecycle));
        controllers.add(new WifiMacAddressPreferenceController(context, lifecycle));
        controllers.add(new BluetoothAddressPreferenceController(context, lifecycle));
        controllers.add(new ManualPreferenceController(context));
        controllers.add(new FeedbackPreferenceController(fragment, context));
        controllers.add(new FccEquipmentIdPreferenceController(context));
        controllers.add(new UptimePreferenceController(context, lifecycle));

        Consumer<String> imeiInfoList = imeiKey -> {
            if (Flags.catalystMyDeviceInfoPrefScreen()) {
                return;
            }
            ImeiInfoPreferenceController imeiRecord =
                    new ImeiInfoPreferenceController(context, imeiKey);
            imeiRecord.init(fragment, slotSimStatus);
            controllers.add(imeiRecord);
        };

        if (fragment != null) {
            imeiInfoList.accept(ImeiInfoPreferenceController.DEFAULT_KEY);
        }

        for (int slotIndex = 0; slotIndex < slotSimStatus.size(); slotIndex++) {
            SimStatusPreferenceController slotRecord =
                    new SimStatusPreferenceController(context,
                            slotSimStatus.getPreferenceKey(slotIndex));
            slotRecord.init(fragment, slotSimStatus);
            controllers.add(slotRecord);

            if (fragment != null) {
                imeiInfoList.accept(ImeiInfoPreferenceController.DEFAULT_KEY + (1 + slotIndex));
            }
        }

        if (!isCatalystEnabled) {
            EidStatus eidStatus = new EidStatus(slotSimStatus, context, executor);
            SimEidPreferenceController simEid = new SimEidPreferenceController(context,
                    KEY_EID_INFO);
            simEid.init(slotSimStatus, eidStatus);
            controllers.add(simEid);
        }

        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdown();
        }
        return controllers;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mBuildNumberPreferenceController.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void initHeader() {
        final PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        final LayoutPreference headerPreference =
                screen.findPreference(KEY_MY_DEVICE_INFO_HEADER);
        if (headerPreference == null) {
            return;
        }
        headerPreference.setVisible(true);

        mBannerWallpaperView = headerPreference.findViewById(R.id.banner_wallpaper);
        updateBannerWallpaper();

        mDeviceNameValueView = headerPreference.findViewById(R.id.device_name_value);
        mDeviceNameCard = headerPreference.findViewById(R.id.device_name_card);
        updateDeviceNameCard();
        if (mDeviceNameCard != null) {
            // Tapping the card opens the rename dialog.
            mDeviceNameCard.setOnClickListener(v -> showDeviceNameEditDialog());
        }

        final TextView versionValue = headerPreference.findViewById(R.id.android_version_value);
        if (versionValue != null) {
            versionValue.setText(Build.VERSION.RELEASE_OR_PREVIEW_DISPLAY);
        }
        final View versionCard = headerPreference.findViewById(R.id.android_version_card);
        if (versionCard != null) {
            // Tapping the card opens the firmware details page directly.
            versionCard.setOnClickListener(v -> {
                final Context context = getContext();
                if (context == null) {
                    return;
                }
                new SubSettingLauncher(context)
                        .setDestination(FirmwareVersionSettings.class.getName())
                        .setSourceMetricsCategory(getMetricsCategory())
                        .launch();
            });
        }

        mStorageWave = headerPreference.findViewById(R.id.storage_wave);
        mStorageValue = headerPreference.findViewById(R.id.storage_value);
        updateStorageCard();
        final View storageCard = headerPreference.findViewById(R.id.storage_card);
        if (storageCard != null) {
            // Tapping the card opens the Storage settings page.
            storageCard.setOnClickListener(v -> {
                final Context context = getContext();
                if (context == null) {
                    return;
                }
                new SubSettingLauncher(context)
                        .setDestination(StorageDashboardFragment.class.getName())
                        .setSourceMetricsCategory(getMetricsCategory())
                        .setArguments(new Bundle())
                        .launch();
            });
        }
        updateSpecsCard(headerPreference);
    }

    private void updateStorageCard() {
        if (mStorageWave == null) {
            return;
        }
        final Context context = getContext();
        if (context == null) {
            return;
        }
        // Fill matches the icon circles; the track blends into the card background,
        // exactly like the reference design.
        final int fillColor = context.getColor(
                com.android.settingslib.widget.theme.R.color
                        .settingslib_materialColorSecondaryContainer);
        final int trackColor = context.getColor(
                com.android.settingslib.widget.theme.R.color
                        .settingslib_materialColorSurfaceBright);
        mStorageWave.setColors(trackColor, fillColor);
        // Same source as the Storage settings page so the numbers always match.
        final Context appContext = context.getApplicationContext();
        ThreadUtils.postOnBackgroundThread(() -> {
            long totalBytes = 0L;
            long freeBytes = 0L;
            final StorageManager storageManager =
                    appContext.getSystemService(StorageManager.class);
            if (storageManager != null) {
                try {
                    final PrivateStorageInfo storageInfo =
                            PrivateStorageInfo.getPrivateStorageInfo(
                                    new StorageManagerVolumeProvider(storageManager));
                    totalBytes = storageInfo.totalBytes;
                    freeBytes = storageInfo.freeBytes;
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Unable to load storage info", e);
                }
            }
            final long usedBytes = Math.max(0L, totalBytes - freeBytes);
            final float fraction = totalBytes > 0 ? (float) usedBytes / totalBytes : 0f;
            final String value = appContext.getString(R.string.my_device_info_storage_used,
                    toWholeGigabytes(usedBytes), toWholeGigabytes(totalBytes));
            ThreadUtils.postOnMainThread(() -> {
                if (mStorageWave == null) {
                    return;
                }
                mStorageWave.setProgress(fraction);
                if (mStorageValue != null) {
                    mStorageValue.setText(value);
                }
            });
        });
    }

    private static long toWholeGigabytes(long bytes) {
        return Math.round(bytes / 1_000_000_000d);
    }

    private void updateSpecsCard(LayoutPreference headerPreference) {
        setHeaderText(headerPreference, R.id.spec_processor, getSocInfo());
        setHeaderText(headerPreference, R.id.spec_ram, getTotalRam());
        setHeaderText(headerPreference, R.id.spec_display, getDisplaySize());
        setHeaderText(headerPreference, R.id.spec_battery, getBatteryCapacity());
    }

    private void setHeaderText(LayoutPreference headerPreference, int viewId,
            CharSequence text) {
        final TextView view = headerPreference.findViewById(viewId);
        if (view != null) {
            view.setText(text);
        }
    }

    private String getSocInfo() {
        String model = SystemProperties.get("ro.soc.model", "");
        if (model.isEmpty()) {
            model = Build.HARDWARE;
        }
        final String manufacturer = SystemProperties.get("ro.soc.manufacturer", "");
        if (!manufacturer.isEmpty()) {
            return model + " (" + manufacturer + ")";
        }
        if (!Build.HARDWARE.isEmpty() && !Build.HARDWARE.equals(model)) {
            return model + " (" + Build.HARDWARE + ")";
        }
        return model;
    }

    private String getTotalRam() {
        final Context context = getContext();
        if (context != null) {
            final ActivityManager activityManager = context.getSystemService(
                    ActivityManager.class);
            if (activityManager != null) {
                final ActivityManager.MemoryInfo memoryInfo =
                        new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memoryInfo);
                // totalMem excludes memory reserved by hardware, so round up to the
                // marketed physical size (e.g. ~7.x GiB on an 8 GB device -> 8 GB).
                final long gigabytes =
                        (long) Math.ceil(memoryInfo.totalMem / (1024d * 1024d * 1024d));
                return getString(R.string.my_device_info_ram_size, gigabytes);
            }
        }
        return getString(R.string.unknown);
    }

    private String getDisplaySize() {
        final Context context = getContext();
        if (context != null) {
            final WindowManager windowManager = context.getSystemService(
                    WindowManager.class);
            if (windowManager != null) {
                final Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
                return bounds.width() + " × " + bounds.height();
            }
        }
        return getString(R.string.unknown);
    }

    private String getBatteryCapacity() {
        final Context context = getContext();
        if (context != null) {
            final Intent batteryIntent = BatteryUtils.getBatteryIntent(context);
            if (batteryIntent != null) {
                final int capacityUah = batteryIntent.getIntExtra(
                        BatteryManager.EXTRA_DESIGN_CAPACITY, -1);
                if (capacityUah > 0) {
                    return context.getString(R.string.battery_design_capacity_summary,
                            capacityUah / 1000);
                }
            }
        }
        return getString(R.string.unknown);
    }

    private void updateBannerWallpaper() {
        if (mBannerWallpaperView == null) {
            return;
        }
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final WallpaperManager wallpaperManager = context.getSystemService(
                WallpaperManager.class);
        if (wallpaperManager == null) {
            return;
        }
        try {
            final Drawable wallpaper = wallpaperManager.getDrawable();
            if (wallpaper != null) {
                mBannerWallpaperView.setImageDrawable(wallpaper);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Unable to load wallpaper for header banner", e);
        }
    }

    private void updateDeviceNameCard() {
        if (mDeviceNameValueView == null) {
            return;
        }
        final String deviceName = getDeviceName();
        mDeviceNameValueView.setText(deviceName);
        if (mDeviceNameCard != null) {
            mDeviceNameCard.setContentDescription(
                    getString(R.string.my_device_info_device_name_preference_title)
                            + ", " + deviceName);
        }
    }

    private String getDeviceName() {
        final Context context = getContext();
        if (context != null) {
            final String deviceName = Settings.Global.getString(context.getContentResolver(),
                    Settings.Global.DEVICE_NAME);
            if (deviceName != null) {
                return deviceName;
            }
        }
        return Build.MODEL;
    }

    private void showDeviceNameEditDialog() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(getDeviceName());
        input.selectAll();
        final FrameLayout container = new FrameLayout(context);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final int padding = context.getResources().getDimensionPixelSize(
                R.dimen.about_phone_device_name_dialog_padding);
        container.setPadding(padding, padding, padding, padding);
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.my_device_info_device_name_preference_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, null /* listener set below */)
                .setNegativeButton(android.R.string.cancel, null /* dismiss */)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final String value = input.getText().toString();
                    final WifiDeviceNameTextValidator validator =
                            new WifiDeviceNameTextValidator();
                    if (!validator.isTextValid(value)) {
                        input.setError(validator.getErrorMessage(value));
                        return;
                    }
                    dialog.dismiss();
                    mPendingDeviceName = value;
                    showDeviceNameWarningDialog(value);
                }));
        dialog.show();
    }

    private void registerHeaderObservers() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        if (mWallpaperChangedReceiver == null) {
            mWallpaperChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateBannerWallpaper();
                }
            };
        }
        context.registerReceiver(mWallpaperChangedReceiver,
                new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);
        if (mDeviceNameObserver == null) {
            mDeviceNameObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    updateDeviceNameCard();
                }
            };
        }
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_NAME),
                false /* notifyForDescendants */, mDeviceNameObserver);
    }

    private void unregisterHeaderObservers() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        if (mWallpaperChangedReceiver != null) {
            context.unregisterReceiver(mWallpaperChangedReceiver);
        }
        if (mDeviceNameObserver != null) {
            context.getContentResolver().unregisterContentObserver(mDeviceNameObserver);
        }
    }

    public void showDeviceNameWarningDialog(String deviceName) {
        DeviceNameWarningDialog.show(this);
    }

    public void onSetDeviceNameConfirm(boolean confirm) {
        final Context context = getContext();
        if (confirm && context != null && mPendingDeviceName != null) {
            // Same helper used by the catalyst migration path: updates Settings.Global,
            // the Bluetooth name and the hotspot SSID.
            UtilsKt.updateDeviceName(context, mPendingDeviceName);
            updateDeviceNameCard();
        }
        mPendingDeviceName = null;
    }

    @Override
    public @Nullable String getPreferenceScreenBindingKey(@NonNull Context context) {
        return MyDeviceInfoScreen.KEY;
    }

    /**
     * For Search.
     */
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.my_device_info) {

                @Override
                public List<AbstractPreferenceController> createPreferenceControllers(
                        Context context) {
                    return buildPreferenceControllers(context, null /* fragment */,
                            null /* lifecycle */);
                }
            };
}
// LINT.ThenChange(MyDeviceInfoScreen.kt, MyDeviceInfoApiFirstScreen.kt)
