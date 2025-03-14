/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.settings;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGammaFloat;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Log;
import android.util.MathUtils;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.BrightnessSynchronizer;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.systemui.Dependency;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.R;

import java.util.ArrayList;

public class BrightnessController implements ToggleSlider.Listener {
    private static final String TAG = "StatusBar.BrightnessController";
    private static final int SLIDER_ANIMATION_DURATION = 3000;

    private static final int MSG_UPDATE_ICON = 0;
    private static final int MSG_UPDATE_SLIDER = 1;
    private static final int MSG_SET_CHECKED = 2;
    private static final int MSG_ATTACH_LISTENER = 3;
    private static final int MSG_DETACH_LISTENER = 4;
    private static final int MSG_VR_MODE_CHANGED = 5;

    private static final Uri BRIGHTNESS_MODE_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE);
    private static final Uri BRIGHTNESS_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);
    private static final Uri BRIGHTNESS_FLOAT_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FLOAT);
    private static final Uri BRIGHTNESS_FOR_VR_FLOAT_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT);

    private final float mMinimumBacklight;
    private final float mMaximumBacklight;
    private final float mDefaultBacklight;
    private final float mMinimumBacklightForVr;
    private final float mMaximumBacklightForVr;
    private final float mDefaultBacklightForVr;

    private final Context mContext;
    private final ToggleSlider mControl;
    private final boolean mAutomaticAvailable;
    private final DisplayManager mDisplayManager;
    private final CurrentUserTracker mUserTracker;
    private final IVrManager mVrManager;

    private final Handler mBackgroundHandler;
    private final BrightnessObserver mBrightnessObserver;

    private final ImageView mIcon;
    private final ImageView mLevelIcon;
    private ImageView mMirrorIcon = null;
    private ImageView mMirrorLevelIcon = null;

    private int mSliderMax = 0;
    private int mSliderValue = 0;

    private ArrayList<BrightnessStateChangeCallback> mChangeCallbacks =
            new ArrayList<BrightnessStateChangeCallback>();

    private volatile boolean mAutomatic;  // Brightness adjusted automatically using ambient light.
    private volatile boolean mIsVrModeEnabled;
    private boolean mListening;
    private boolean mExternalChange;
    private boolean mControlValueInitialized;

    private ValueAnimator mSliderAnimator;
    private float mBrightnessRampRate;

    public interface BrightnessStateChangeCallback {
        public void onBrightnessLevelChanged();
    }

    /** ContentObserver to watch brightness */
    private class BrightnessObserver extends ContentObserver {

        public BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (selfChange) return;

            if (BRIGHTNESS_MODE_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else if (BRIGHTNESS_FLOAT_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else if (BRIGHTNESS_FOR_VR_FLOAT_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            }
            for (BrightnessStateChangeCallback cb : mChangeCallbacks) {
                cb.onBrightnessLevelChanged();
            }
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    BRIGHTNESS_MODE_URI,
                    false, this, UserHandle.USER_ALL);
            cr.registerContentObserver(
                    BRIGHTNESS_URI,
                    false, this, UserHandle.USER_ALL);
            cr.registerContentObserver(
                    BRIGHTNESS_FLOAT_URI,
                    false, this, UserHandle.USER_ALL);
            cr.registerContentObserver(
                    BRIGHTNESS_FOR_VR_FLOAT_URI,
                    false, this, UserHandle.USER_ALL);
        }

        public void stopObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
        }

    }

    private final Runnable mStartListeningRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListening) {
                return;
            }
            mListening = true;

            if (mVrManager != null) {
                try {
                    mVrManager.registerListener(mVrStateCallbacks);
                    mIsVrModeEnabled = mVrManager.getVrModeState();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register VR mode state listener: ", e);
                }
            }

            mBrightnessObserver.startObserving();
            mUserTracker.startTracking();

            // Update the slider and mode before attaching the listener so we don't
            // receive the onChanged notifications for the initial values.
            mUpdateModeRunnable.run();
            mUpdateSliderRunnable.run();

            mHandler.sendEmptyMessage(MSG_ATTACH_LISTENER);
        }
    };

    private final Runnable mStopListeningRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mListening) {
                return;
            }
            mListening = false;

            if (mVrManager != null) {
                try {
                    mVrManager.unregisterListener(mVrStateCallbacks);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to unregister VR mode state listener: ", e);
                }
            }

            mBrightnessObserver.stopObserving();
            mUserTracker.stopTracking();

            mHandler.sendEmptyMessage(MSG_DETACH_LISTENER);
        }
    };

    /**
     * Fetch the brightness mode from the system settings and update the icon. Should be called from
     * background thread.
     */
    private final Runnable mUpdateModeRunnable = new Runnable() {
        @Override
        public void run() {
            if (mAutomaticAvailable) {
                int automatic;
                automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                        UserHandle.USER_CURRENT);
                mAutomatic = automatic != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
                mHandler.obtainMessage(MSG_UPDATE_ICON, mAutomatic ? 1 : 0).sendToTarget();
            } else {
                mHandler.obtainMessage(MSG_SET_CHECKED, 0).sendToTarget();
                mHandler.obtainMessage(MSG_UPDATE_ICON, 0).sendToTarget();
            }
        }
    };

    /**
     * Fetch the brightness from the system settings and update the slider. Should be called from
     * background thread.
     */
    private final Runnable mUpdateSliderRunnable = new Runnable() {
        @Override
        public void run() {
            final float valFloat;
            final boolean inVrMode = mIsVrModeEnabled;
            if (inVrMode) {
                valFloat = Settings.System.getFloatForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT, mDefaultBacklightForVr,
                        UserHandle.USER_CURRENT);
            } else {
                valFloat = Settings.System.getFloatForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_FLOAT, mDefaultBacklight,
                        UserHandle.USER_CURRENT);
            }
            // Value is passed as intbits, since this is what the message takes.
            final int valueAsIntBits = Float.floatToIntBits(valFloat);
            mHandler.obtainMessage(MSG_UPDATE_SLIDER, valueAsIntBits,
                    inVrMode ? 1 : 0).sendToTarget();
        }
    };

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mHandler.obtainMessage(MSG_VR_MODE_CHANGED, enabled ? 1 : 0, 0)
                    .sendToTarget();
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            mExternalChange = true;
            try {
                switch (msg.what) {
                    case MSG_UPDATE_ICON:
                        updateIcon(msg.arg1 != 0);
                    break;
                    case MSG_UPDATE_SLIDER:
                        updateSlider(Float.intBitsToFloat(msg.arg1), msg.arg2 != 0);
                        updateIcon(mAutomatic);
                        break;
                    case MSG_SET_CHECKED:
                        mControl.setChecked(msg.arg1 != 0);
                        break;
                    case MSG_ATTACH_LISTENER:
                        mControl.setOnChangedListener(BrightnessController.this);
                        break;
                    case MSG_DETACH_LISTENER:
                        mControl.setOnChangedListener(null);
                        break;
                    case MSG_VR_MODE_CHANGED:
                        updateVrMode(msg.arg1 != 0);
                        break;
                    default:
                        super.handleMessage(msg);
                }
            } finally {
                mExternalChange = false;
            }
        }
    };

    public BrightnessController(Context context, ImageView levelIcon, ImageView icon,
            ToggleSlider control, BroadcastDispatcher broadcastDispatcher) {
        mContext = context;
        mIcon = icon;
        mLevelIcon = levelIcon;
        if (mIcon != null) {
            mIcon.setOnClickListener((View v) -> {
                onClickAutomaticIcon();
            });
        }
        mSliderMax = GAMMA_SPACE_MAX;
        mControl = control;
        mControl.setMax(GAMMA_SPACE_MAX);
        mBackgroundHandler = new Handler((Looper) Dependency.get(Dependency.BG_LOOPER));
        mUserTracker = new CurrentUserTracker(broadcastDispatcher) {
            @Override
            public void onUserSwitched(int newUserId) {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            }
        };
        mBrightnessObserver = new BrightnessObserver(mHandler);

        PowerManager pm = context.getSystemService(PowerManager.class);
        mMinimumBacklight = pm.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM);
        mMaximumBacklight = pm.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM);
        mDefaultBacklight = pm.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT);
        mMinimumBacklightForVr = pm.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM_VR);
        mMaximumBacklightForVr = pm.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM_VR);
        mDefaultBacklightForVr = pm.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT_VR);


        mAutomaticAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mVrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                Context.VR_SERVICE));
        mBrightnessRampRate = mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_brightnessRampRateFastFloat);

        updateIcon(mAutomatic);
    }

    public void addStateChangedCallback(BrightnessStateChangeCallback cb) {
        mChangeCallbacks.add(cb);
    }

    public boolean removeStateChangedCallback(BrightnessStateChangeCallback cb) {
        return mChangeCallbacks.remove(cb);
    }

    @Override
    public void onInit(ToggleSlider control) {
        // Do nothing
    }

    public void registerCallbacks() {
        mBackgroundHandler.post(mStartListeningRunnable);
    }

    /** Unregister all call backs, both to and from the controller */
    public void unregisterCallbacks() {
        mBackgroundHandler.post(mStopListeningRunnable);
        mControlValueInitialized = false;
    }

    @Override
    public void onChanged(ToggleSlider toggleSlider, boolean tracking, boolean automatic,
            int value, boolean stopTracking) {
        mSliderValue = value;
        updateIcon(mAutomatic);
        if (mExternalChange) return;

        if (mSliderAnimator != null) {
            mSliderAnimator.cancel();
        }

        final float minBacklight;
        final float maxBacklight;
        final int metric;
        final String settingToChange;

        if (mIsVrModeEnabled) {
            metric = MetricsEvent.ACTION_BRIGHTNESS_FOR_VR;
            minBacklight = mMinimumBacklightForVr;
            maxBacklight = mMaximumBacklightForVr;
            settingToChange = Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT;
        } else {
            metric = mAutomatic
                    ? MetricsEvent.ACTION_BRIGHTNESS_AUTO
                    : MetricsEvent.ACTION_BRIGHTNESS;
            minBacklight = mMinimumBacklight;
            maxBacklight = mMaximumBacklight;
            settingToChange = Settings.System.SCREEN_BRIGHTNESS_FLOAT;
        }
        final float valFloat = MathUtils.min(convertGammaToLinearFloat(value,
                minBacklight, maxBacklight),
                1.0f);
        if (stopTracking) {
            // TODO(brightnessfloat): change to use float value instead.
            MetricsLogger.action(mContext, metric,
                    BrightnessSynchronizer.brightnessFloatToInt(mContext, valFloat));

        }
        setBrightness(valFloat);
        if (!tracking) {
            AsyncTask.execute(new Runnable() {
                    public void run() {
                        Settings.System.putFloatForUser(mContext.getContentResolver(),
                                settingToChange, valFloat, UserHandle.USER_CURRENT);
                    }
                });
        }

        for (BrightnessStateChangeCallback cb : mChangeCallbacks) {
            cb.onBrightnessLevelChanged();
        }
    }

    public void checkRestrictionAndSetEnabled() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                ((ToggleSliderView)mControl).setEnforcedAdmin(
                        RestrictedLockUtilsInternal.checkIfRestrictionEnforced(mContext,
                                UserManager.DISALLOW_CONFIG_BRIGHTNESS,
                                mUserTracker.getCurrentUserId()));
            }
        });
    }

    private void setMode(int mode) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, mode,
                mUserTracker.getCurrentUserId());
    }

    private void setBrightness(float brightness) {
        mDisplayManager.setTemporaryBrightness(brightness);
    }

    private void updateIcon(boolean automatic) {
        updateIconInternal(automatic, mIcon, mLevelIcon);
        updateIconInternal(automatic, mMirrorIcon, mMirrorLevelIcon);
    }

    private void updateIconInternal(boolean automatic, ImageView icon, ImageView levelIcon) {
        if (icon != null) {
            if (automatic) {
                icon.setImageResource(R.drawable.ic_qs_brightness_auto_on);
            } else {
                icon.setImageResource(R.drawable.ic_qs_brightness_auto_off);
            }
        }
        if (levelIcon == null) {
            return;
        }
        if (mIsVrModeEnabled) {
            if (((float) mSliderValue) <= mMinimumBacklightForVr) {
                levelIcon.setImageResource(R.drawable.ic_qs_brightness_low);
            } else if (mSliderValue >= mSliderMax - 1) {
                levelIcon.setImageResource(R.drawable.ic_qs_brightness_high);
            } else {
                levelIcon.setImageResource(R.drawable.ic_qs_brightness_medium);
            }
        } else {
            if (((float) mSliderValue) <= mMinimumBacklight) {
                levelIcon.setImageResource(R.drawable.ic_qs_brightness_low);
            } else if (mSliderValue >= mSliderMax - 1) {
                levelIcon.setImageResource(R.drawable.ic_qs_brightness_high);
            } else {
                levelIcon.setImageResource(R.drawable.ic_qs_brightness_medium);
            }
        }
    }
    
    private void updateVrMode(boolean isEnabled) {
        if (mIsVrModeEnabled != isEnabled) {
            mIsVrModeEnabled = isEnabled;
            mBackgroundHandler.post(mUpdateSliderRunnable);
        }
    }

    private void updateSlider(float brightnessValue, boolean inVrMode) {
        final float min;
        final float max;
        if (inVrMode) {
            min = mMinimumBacklightForVr;
            max = mMaximumBacklightForVr;
        } else {
            min = mMinimumBacklight;
            max = mMaximumBacklight;
        }
        // convertGammaToLinearFloat returns 0-1
        if (BrightnessSynchronizer.floatEquals(brightnessValue,
                convertGammaToLinearFloat(mControl.getValue(), min, max))) {
            // If the value in the slider is equal to the value on the current brightness
            // then the slider does not need to animate, since the brightness will not change.
            return;
        }
        // Returns GAMMA_SPACE_MIN - GAMMA_SPACE_MAX
        final int sliderVal = convertLinearToGammaFloat(brightnessValue, min, max);
        animateSliderTo(sliderVal);
    }

    private void animateSliderTo(int target) {
        if (!mControlValueInitialized) {
            // Don't animate the first value since its default state isn't meaningful to users.
            mControl.setValue(target);
            mControlValueInitialized = true;
        }
        if (mSliderAnimator != null && mSliderAnimator.isStarted()) {
            mSliderAnimator.cancel();
        }
        mSliderAnimator = ValueAnimator.ofInt(mControl.getValue(), target);
        mSliderAnimator.addUpdateListener((ValueAnimator animation) -> {
            mExternalChange = true;
            mControl.setValue((int) animation.getAnimatedValue());
            mExternalChange = false;
        });
        final long animationDuration = Math.round(SLIDER_ANIMATION_DURATION * Math.abs(
                mControl.getValue() - target)/mBrightnessRampRate) / GAMMA_SPACE_MAX;
        mSliderAnimator.setDuration(animationDuration);
        mSliderAnimator.start();
    }

    public void onClickAutomaticIcon() {
        setMode(!mAutomatic ? 1 : 0);
    }

    public void setMirrorView(View v) {
        mMirrorIcon = v.findViewById(R.id.brightness_icon);
        mMirrorLevelIcon = v.findViewById(R.id.brightness_level);
    }

}
