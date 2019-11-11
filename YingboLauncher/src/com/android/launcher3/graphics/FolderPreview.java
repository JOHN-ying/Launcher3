package com.android.launcher3.graphics;

import static com.android.launcher3.Utilities.getPrefs;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.SystemClock;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.LooperExecutor;

import java.lang.reflect.Field;

/**
 * Utility class to override shape of {@link android.graphics.drawable.AdaptiveIconDrawable}.
 */
@TargetApi(Build.VERSION_CODES.O)
public class FolderPreview {

    private static final String TAG = "FolderPreview";

    public static final String FOLDER_PREVIEW= "folder_preview";

    // Time to wait before killing the process this ensures that the progress bar is visible for
    // sufficient time so that there is no flicker.
    private static final long PROCESS_KILL_DELAY_MS = 1500;

    private static final int RESTART_REQUEST_CODE = 42; // the answer to everything

    public static boolean isSupported(Context context) {
        if (!Utilities.ATLEAST_OREO) {
            return false;
        }
        // Only supported when developer settings is enabled
        if (Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1) {
            return false;
        }

        try {
            if (getSystemResField().get(null) != Resources.getSystem()) {
                // Our assumption that mSystem is the system resource is not true.
                return false;
            }
        } catch (Exception e) {
            // Ignore, not supported
            return false;
        }

        return getConfigResId() != 0;
    }

    public static void apply(Context context) {
        if (!Utilities.ATLEAST_OREO) {
            return;
        }
        String path = getAppliedValue(context);
        if (TextUtils.isEmpty(path)) {
            return;
        }
        if (!isSupported(context)) {
            return;
        }

        // magic
        try {
            Resources override =
                    new ResourcesOverride(Resources.getSystem(), getConfigResId(), path);
            getSystemResField().set(null, override);
        } catch (Exception e) {
            Log.e(TAG, "Unable to override icon shape", e);
            // revert value.
            getPrefs(context).edit().remove(FOLDER_PREVIEW).apply();
        }
    }

    private static Field getSystemResField() throws Exception {
        Field staticField = Resources.class.getDeclaredField("mSystem");
        staticField.setAccessible(true);
        return staticField;
    }

    private static int getConfigResId() {
        return Resources.getSystem().getIdentifier("config_icon_mask", "string", "android");
    }

    private static String getAppliedValue(Context context) {
        return getPrefs(context).getString(FOLDER_PREVIEW, "0");
    }

    public static void handlePreferenceUi(ListPreference preference) {
        Context context = preference.getContext();
        preference.setValue(getAppliedValue(context));
        preference.setOnPreferenceChangeListener(new PreferenceChangeHandler(context));
    }

    private static class ResourcesOverride extends Resources {

        private final int mOverrideId;
        private final String mOverrideValue;

        @SuppressWarnings("deprecation")
        public ResourcesOverride(Resources parent, int overrideId, String overrideValue) {
            super(parent.getAssets(), parent.getDisplayMetrics(), parent.getConfiguration());
            mOverrideId = overrideId;
            mOverrideValue = overrideValue;
        }

        @NonNull
        @Override
        public String getString(int id) throws NotFoundException {
            if (id == mOverrideId) {
                return mOverrideValue;
            }
            return super.getString(id);
        }
    }

    private static class PreferenceChangeHandler implements OnPreferenceChangeListener {

        private final Context mContext;

        private PreferenceChangeHandler(Context context) {
            mContext = context;
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object o) {
            String newValue = (String) o;
            if (!getAppliedValue(mContext).equals(newValue)) {
                // Value has changed
                ProgressDialog.show(mContext,
                        null /* title */,
                        mContext.getString(R.string.folder_preview_override_progress),
                        true /* indeterminate */,
                        false /* cancelable */);
                new LooperExecutor(LauncherModel.getWorkerLooper()).execute(
                        new OverrideApplyHandler(mContext, newValue));
            }
            return false;
        }
    }

    private static class OverrideApplyHandler implements Runnable {

        private final Context mContext;
        private final String mValue;

        private OverrideApplyHandler(Context context, String value) {
            mContext = context;
            mValue = value;
        }

        @Override
        public void run() {
            // Synchronously write the preference.
            getPrefs(mContext).edit().putString(FOLDER_PREVIEW, mValue).commit();
            // Clear the icon cache.
//            LauncherAppState.getInstance(mContext).getIconCache().clear();

            // Wait for it
            try {
                Thread.sleep(PROCESS_KILL_DELAY_MS);
            } catch (Exception e) {
                Log.e(TAG, "Error waiting", e);
            }

            // Schedule an alarm before we kill ourself.
            Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(mContext.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(mContext, RESTART_REQUEST_CODE,
                    homeIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
            mContext.getSystemService(AlarmManager.class).setExact(
                    AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 50, pi);

            // Kill process
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}

