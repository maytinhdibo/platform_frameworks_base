/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.biometrics.BiometricSourceType;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;

import com.android.internal.util.octavi.OctaviUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.tuner.TunerService;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView implements TunerService.Tunable {
    private static final String DOZE_INTENT = "com.android.systemui.doze.pulse";
    private static final String FOD_GESTURE = "system:" + Settings.System.FOD_GESTURE;
    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final boolean mHideFodCircleGoingToSleep;
    private final boolean mShouldBoostBrightness;
    private final Paint mPaintFingerprintBackground = new Paint();
    private final LayoutParams mParams = new LayoutParams();
    private final LayoutParams mPressedParams = new LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;

    private int mColorBackground;
    private int mDreamingOffsetY;

    private boolean mIsBouncer;
    private boolean mIsBiometricRunning;
    private boolean mIsCircleShowing;
    private boolean mIsDreaming;
    private boolean mIsKeyguard;
    private boolean mTouchedOutside;

    private boolean mDozeEnabled;
    private boolean mFodGestureEnable;
    private boolean mPressPending;
    private boolean mScreenTurnedOn;

    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;

    private Handler mHandler;

    private final ImageView mPressedView;

    private LockPatternUtils mLockPatternUtils;

    private Timer mBurnInProtectionTimer;

    private FODAnimation mFODAnimation;
    private boolean mIsRecognizingAnimEnabled;
    private boolean mIsFodAnimationAvailable = false;

    private int mSelectedIcon;
    private final int[] ICON_STYLES = {
        R.drawable.fod_icon_default,
        R.drawable.fod_icon_default_0,
        R.drawable.fod_icon_default_1,
        R.drawable.fod_icon_default_2,
        R.drawable.fod_icon_default_3,
        R.drawable.fod_icon_default_4,
        R.drawable.fod_icon_madness,
        R.drawable.fod_icon_arc_reactor,
        R.drawable.fod_icon_octavi,
        R.drawable.fod_icon_zaid1,
        R.drawable.fod_icon_zaid2,
        R.drawable.fod_icon_zaid3,
        R.drawable.fod_icon_glow_circle,
        R.drawable.fod_icon_neon_arc,
        R.drawable.fod_icon_neon_arc_gray,
        R.drawable.fod_icon_neon_circle_pink,
        R.drawable.fod_icon_neon_triangle,
        R.drawable.fod_icon_whatever,
        R.drawable.fod_icon_unfunnyguy,
        R.drawable.fod_icon_zaid4,
        R.drawable.fod_icon_zaid5,
        R.drawable.fod_icon_sun_metro,
        R.drawable.fod_icon_sammy,
        R.drawable.fod_icon_op_white,
        R.drawable.fod_icon_zaid6,
        R.drawable.fod_icon_light,
        R.drawable.fod_icon_gxzw,
        R.drawable.fod_icon_transparent
    };

    private int mDefaultPressedColor;
    private int mPressedColor;
    private final int[] PRESSED_COLOR = {
        R.drawable.fod_icon_pressed,
        R.drawable.fod_icon_pressed_cyan,
        R.drawable.fod_icon_pressed_green,
        R.drawable.fod_icon_pressed_yellow,
        R.drawable.fod_icon_pressed_light_yellow
    };

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            if (mFodGestureEnable && !mScreenTurnedOn) {
                if (mDozeEnabled) {
                    mHandler.post(() -> mContext.sendBroadcast(new Intent(DOZE_INTENT)));
                } else {
                    mWakeLock.acquire(3000);
                    mHandler.post(() -> mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_GESTURE, FODCircleView.class.getSimpleName()));
                }
                mPressPending = true;
            } else {
                mHandler.post(() -> showCircle());
            }
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
            if (mPressPending) {
                mPressPending = false;
            }
        }
    };

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            // We assume that if biometricSourceType matches Fingerprint it will be
            // handled here, so we hide only when other biometric types authenticate
            if (biometricSourceType != BiometricSourceType.FINGERPRINT) {
                hide();
            }
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                mIsBiometricRunning = running;
            }
        }

        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateAlpha();

            if (mIsKeyguard && mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
                updateAlpha();
            } else {
                hide();
            }

            if (dreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
                updatePosition();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mIsKeyguard = showing;
            updateStyle();
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.setAnimationKeyguard(mIsKeyguard);
            }
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            updateStyle();
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                if (isPinOrPattern(mUpdateMonitor.getCurrentUser()) || !isBouncer) {
                    show();
                } else {
                    hide();
                }
            } else {
                hide();
            }
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.setAnimationKeyguard(mIsBouncer);
            }
        }

        @Override
        public void onStartedGoingToSleep(int why) {
            if (mHideFodCircleGoingToSleep) {
                hide();
            }
        }

        @Override
        public void onScreenTurnedOff() {
            mScreenTurnedOn = false;
            if (!mFodGestureEnable &&
                    !mHideFodCircleGoingToSleep) {
                hide();
            } else {
                hideCircle();
            }
        }

        @Override
        public void onStartedWakingUp() {
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
        }

        @Override
        public void onScreenTurnedOn() {
            if (mUpdateMonitor.isFingerprintDetectionRunning() && !mFodGestureEnable) {
                show();
            }
            if (mPressPending) {
                mHandler.post(() -> showCircle());
                mPressPending = false;
            }
            mScreenTurnedOn = true;
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            if (msgId == -1 && mIsFodAnimationAvailable) { // Auth error
                mHandler.post(() -> mFODAnimation.hideFODanimation());
            }
        }
    };

    public FODCircleView(Context context) {
        super(context);

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = mContext.getResources();

        mColorBackground = res.getColor(R.color.config_fodColorBackground);
        mDefaultPressedColor = res.getInteger(com.android.internal.R.
             integer.config_fod_pressed_color);
        mPaintFingerprintBackground.setColor(mColorBackground);
        mPaintFingerprintBackground.setAntiAlias(true);

        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                 FODCircleView.class.getSimpleName());

        mHideFodCircleGoingToSleep = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_hideFodCircleGoingToSleep);

        mWindowManager = mContext.getSystemService(WindowManager.class);
        mIsFodAnimationAvailable = OctaviUtils.isPackageInstalled(context,
                                    context.getResources().getString(
                                    com.android.internal.R.string.config_fodAnimationPackage));
        if (mIsFodAnimationAvailable) {
            mFODAnimation = new FODAnimation(mContext, mWindowManager, mPositionX, mPositionY);
        }

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mPressedParams.copyFrom(mParams);
        mPressedParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

        mParams.setTitle("Fingerprint on display");
        mPressedParams.setTitle("Fingerprint on display.touched");

        mPressedView = new ImageView(mContext)  {
            @Override
            protected void onDraw(Canvas canvas) {
                if (mIsCircleShowing) {
                    setImageResource(PRESSED_COLOR[mPressedColor]);
                }
                super.onDraw(canvas);
            }
        };

        mWindowManager.addView(this, mParams);

        mCustomSettingsObserver.observe();
        mCustomSettingsObserver.update();
        updatePosition();
        hide();

        mLockPatternUtils = new LockPatternUtils(mContext);

        mUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        Dependency.get(TunerService.class).addTunable(this, FOD_GESTURE,
                Settings.Secure.DOZE_ENABLED);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (key.equals(FOD_GESTURE)) {
            mFodGestureEnable = TunerService.parseIntegerSwitch(newValue, false);
        } else {
            mDozeEnabled = TunerService.parseIntegerSwitch(newValue, true);
        }
    }

    private CustomSettingsObserver mCustomSettingsObserver = new CustomSettingsObserver(mHandler);
    private class CustomSettingsObserver extends ContentObserver {

        CustomSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.FOD_ANIM),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.FOD_ICON),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.FOD_COLOR),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(Settings.System.FOD_ANIM)) ||
                    uri.equals(Settings.System.getUriFor(Settings.System.FOD_ICON)) ||
                    uri.equals(Settings.System.getUriFor(Settings.System.FOD_COLOR))) {
                updateStyle();
            }
        }

        public void update() {
            updateStyle();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mIsCircleShowing) {
            canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprintBackground);
        }
        super.onDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);
        mTouchedOutside = false;

        if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mTouchedOutside = true;
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            return true;
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updateStyle();
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void dispatchPress() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        if (mTouchedOutside) return;
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        setDim(true);
        dispatchPress();

        setImageDrawable(null);
        invalidate();
        if (mIsRecognizingAnimEnabled) {
            mFODAnimation.showFODanimation();
        }
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        setImageResource(ICON_STYLES[mSelectedIcon]);
        invalidate();

        dispatchRelease();
        setDim(false);

        setKeepScreenOn(false);
        if (mIsRecognizingAnimEnabled) {
            mFODAnimation.hideFODanimation();
        }
    }

    public void show() {
        if (!mUpdateMonitor.isScreenOn() && !mFodGestureEnable) {
            // Keyguard is shown just after screen turning off
            return;
        }

        if (mIsBouncer && !isPinOrPattern(mUpdateMonitor.getCurrentUser())) {
            // Ignore show calls when Keyguard password screen is being shown
            return;
        }

        if (mIsKeyguard && mUpdateMonitor.getUserCanSkipBouncer(mUpdateMonitor.getCurrentUser())) {
            // Ignore show calls if user can skip bouncer
            return;
        }

        if (mIsKeyguard && !mIsBiometricRunning) {
            return;
        }

        updatePosition();

        setVisibility(View.VISIBLE);
        dispatchShow();
    }

    public void hide() {
        setVisibility(View.GONE);
        hideCircle();
        dispatchHide();
    }

    private void updateAlpha() {
        setAlpha(mIsDreaming ? 0.5f : 1.0f);
    }

    private void updateStyle() {
        mIsRecognizingAnimEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_RECOGNIZING_ANIMATION, 0) != 0;
        mSelectedIcon = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_ICON, 0);
        mPressedColor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.FOD_COLOR, mDefaultPressedColor);

        if (mIsFodAnimationAvailable && mFODAnimation != null) {
            mFODAnimation.update(mIsRecognizingAnimEnabled);
        }
    }

    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        int x, y;
        switch (rotation) {
            case Surface.ROTATION_0:
                x = mPositionX;
                y = mPositionY;
                break;
            case Surface.ROTATION_90:
                x = mPositionY;
                y = mPositionX;
                break;
            case Surface.ROTATION_180:
                x = mPositionX;
                y = size.y - mPositionY - mSize;
                break;
            case Surface.ROTATION_270:
                x = size.x - mPositionY - mSize - mNavigationBarSize;
                y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        mPressedParams.x = mParams.x = x;
        mPressedParams.y = mParams.y = y;

        if (mIsDreaming) {
            mParams.y += mDreamingOffsetY;
            if (mIsRecognizingAnimEnabled) {
                mFODAnimation.updateParams(mParams.y);
            }
        }

        mWindowManager.updateViewLayout(this, mParams);

        if (mPressedView.getParent() != null) {
            mWindowManager.updateViewLayout(mPressedView, mPressedParams);
        }
    }

    private void setDim(boolean dim) {
        if (dim) {
            int curBrightness = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 100);
            int dimAmount = 0;

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(curBrightness);
            } catch (RemoteException e) {
                // do nothing
            }

            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 1.0f;
            }

            mPressedParams.dimAmount = dimAmount / 255.0f;
            if (mPressedView.getParent() == null) {
                mWindowManager.addView(mPressedView, mPressedParams);
            } else {
                mWindowManager.updateViewLayout(mPressedView, mPressedParams);
            }
        } else {
            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 0.0f;
            }
            mPressedParams.dimAmount = 0.0f;
            if (mPressedView.getParent() != null) {
                mWindowManager.removeView(mPressedView);
            }
        }
    }

    private boolean isPinOrPattern(int userId) {
        int passwordQuality = mLockPatternUtils.getActivePasswordQuality(userId);
        switch (passwordQuality) {
            // PIN
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
            // Pattern
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                return true;
        }

        return false;
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 1000 / 60;

            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            mDreamingOffsetY -= mDreamingMaxOffset;

            mHandler.post(() -> updatePosition());
        }
    };
}
