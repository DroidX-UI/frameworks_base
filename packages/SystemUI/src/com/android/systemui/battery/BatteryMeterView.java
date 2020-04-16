/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.battery;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StyleRes;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.graph.CircleBatteryDrawable;
import com.android.settingslib.graph.ThemedBatteryDrawable;
import com.android.systemui.DualToneHandler;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.BatteryController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.text.NumberFormat;
import java.util.ArrayList;

public class BatteryMeterView extends LinearLayout implements DarkReceiver {

    protected static final int BATTERY_STYLE_PORTRAIT = 0;
    protected static final int BATTERY_STYLE_CIRCLE = 1;
    protected static final int BATTERY_STYLE_DOTTED_CIRCLE = 2;
    protected static final int BATTERY_STYLE_TEXT = 3;
    protected static final int BATTERY_STYLE_HIDDEN = 4;

    @Retention(SOURCE)
    @IntDef({MODE_DEFAULT, MODE_ON, MODE_OFF, MODE_ESTIMATE})
    public @interface BatteryPercentMode {}
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_OFF = 2;
    public static final int MODE_ESTIMATE = 3;

    private final CircleBatteryDrawable mCircleDrawable;
    private final ThemedBatteryDrawable mThemedDrawable;
    private final ImageView mBatteryIconView;
    private TextView mBatteryPercentView;

    private final @StyleRes int mPercentageStyleId;
    private int mTextColor;
    private int mLevel;
    private int mShowPercentMode = MODE_DEFAULT;
    private boolean mCharging;
    // Error state where we know nothing about the current battery state
    private boolean mBatteryStateUnknown;
    // Lazily-loaded since this is expected to be a rare-if-ever state
    private Drawable mUnknownStateDrawable;

    private int mBatteryStyle = BATTERY_STYLE_PORTRAIT;
    private int mShowBatteryPercent;
    private boolean mBatteryPercentCharging;

    private DualToneHandler mDualToneHandler;

    private int mNonAdaptedSingleToneColor;
    private int mNonAdaptedForegroundColor;
    private int mNonAdaptedBackgroundColor;

    private BatteryEstimateFetcher mBatteryEstimateFetcher;

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(R.color.meter_background_color));
        mPercentageStyleId = atts.getResourceId(R.styleable.BatteryMeterView_textAppearance, 0);
        mCircleDrawable = new CircleBatteryDrawable(context, frameColor);
        mThemedDrawable = new ThemedBatteryDrawable(context, frameColor);
        atts.recycle();

        setupLayoutTransition();

        mBatteryIconView = new ImageView(context);
        mBatteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_BATTERY_STYLE, BATTERY_STYLE_PORTRAIT, UserHandle.USER_CURRENT);
        updateDrawable();

        int batteryHeight = mBatteryStyle == BATTERY_STYLE_CIRCLE || mBatteryStyle == BATTERY_STYLE_DOTTED_CIRCLE ?
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width) :
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
        int batteryWidth = mBatteryStyle == BATTERY_STYLE_CIRCLE || mBatteryStyle == BATTERY_STYLE_DOTTED_CIRCLE ?
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width) :
                getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);

        final MarginLayoutParams mlp = new MarginLayoutParams(batteryWidth, batteryHeight);
        mlp.setMargins(0, 0, 0,
                getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
        addView(mBatteryIconView, mlp);

        updatePercentView();
        updateVisibility();

        mDualToneHandler = new DualToneHandler(context);
        // Init to not dark at all.
        onDarkChanged(new ArrayList<Rect>(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        setClipChildren(false);
        setClipToPadding(false);
    }

    private void setupLayoutTransition() {
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200);

        // Animates appearing/disappearing of the battery percentage text using fade-in/fade-out
        // and disables all other animation types
        ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
        transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

        ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

        transition.setAnimator(LayoutTransition.CHANGE_APPEARING, null);
        transition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, null);
        transition.setAnimator(LayoutTransition.CHANGING, null);

        setLayoutTransition(transition);
    }

    public void setBatteryStyle(int batteryStyle) {
        if (batteryStyle == mBatteryStyle) return;
        mBatteryStyle = batteryStyle;
        updateBatteryStyle();
    }

    protected void updateBatteryStyle() {
        updateDrawable();
        scaleBatteryMeterViews();
        updatePercentView();
        updateVisibility();
    }

    protected void setBatteryPercent(int showBatteryPercent) {
        if (showBatteryPercent == mShowBatteryPercent) return;
        mShowBatteryPercent = showBatteryPercent;
        updatePercentView();
    }

    protected void setBatteryPercentCharging(boolean batteryPercentCharging) {
        if (batteryPercentCharging == mBatteryPercentCharging) return;
        mBatteryPercentCharging = batteryPercentCharging;
        updatePercentView();
    }

    public void setForceShowPercent(boolean show) {
        setPercentShowMode(show ? MODE_ON : MODE_DEFAULT);
    }

    /**
     * Force a particular mode of showing percent
     *
     * 0 - No preference
     * 1 - Force on
     * 2 - Force off
     * 3 - Estimate
     * @param mode desired mode (none, on, off)
     */
    public void setPercentShowMode(@BatteryPercentMode int mode) {
        if (mode == mShowPercentMode) return;
        mShowPercentMode = mode;
        updateShowPercent();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateBatteryStyle();
    }

    public void setColorsFromContext(Context context) {
        if (context == null) {
            return;
        }

        mDualToneHandler.setColorsFromContext(context);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    void onBatteryLevelChanged(int level, boolean pluggedIn) {
        if (mLevel != level) {
            mLevel = level;
            mThemedDrawable.setBatteryLevel(mLevel);
            mCircleDrawable.setBatteryLevel(mLevel);
        }
        if (mCharging != pluggedIn) {
            mCharging = pluggedIn;
            mThemedDrawable.setCharging(mCharging);
            mCircleDrawable.setCharging(mCharging);
            updateShowPercent();
        } else {
            updatePercentText();
        }
    }

    void onPowerSaveChanged(boolean isPowerSave) {
        mCircleDrawable.setPowerSaveEnabled(isPowerSave);
        mThemedDrawable.setPowerSaveEnabled(isPowerSave);
    }

    private TextView loadPercentView() {
        return (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.battery_percentage_view, null);
    }

    /**
     * Updates percent view by removing old one and reinflating if necessary
     */
    public void updatePercentView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
        updateShowPercent();
    }

    /**
     * Sets the fetcher that should be used to get the estimated time remaining for the user's
     * battery.
     */
    void setBatteryEstimateFetcher(BatteryEstimateFetcher fetcher) {
        mBatteryEstimateFetcher = fetcher;
    }

    void updatePercentText() {
        if (mBatteryStateUnknown) {
            setContentDescription(getContext().getString(R.string.accessibility_battery_unknown));
            return;
        }

        if (mBatteryPercentView != null) {
            if (mBatteryEstimateFetcher != null && mShowPercentMode == MODE_ESTIMATE && !mCharging) {
                mBatteryEstimateFetcher.fetchBatteryTimeRemainingEstimate(
                        (String estimate) -> {
                    if (mBatteryPercentView == null) {
                        return;
                    }
                    if (estimate != null && mShowPercentMode == MODE_ESTIMATE) {
                        mBatteryPercentView.setText(estimate);
                        setContentDescription(getContext().getString(
                                R.string.accessibility_battery_level_with_estimate,
                                mLevel, estimate));
                    } else {
                        setPercentTextAtCurrentLevel();
                    }
                });
            } else {
                setPercentTextAtCurrentLevel();
            }
        } else {
            setContentDescription(
                    getContext().getString(mCharging ? R.string.accessibility_battery_level_charging
                            : R.string.accessibility_battery_level, mLevel));
        }
    }

    private void setPercentTextAtCurrentLevel() {

        String percentText = NumberFormat.getPercentInstance().format(mLevel / 100f);
        // Setting text actually triggers a layout pass (because the text view is set to
        // wrap_content width and TextView always relayouts for this). Avoid needless
        // relayout if the text didn't actually change.
        if (!TextUtils.equals(mBatteryPercentView.getText(), percentText)) {
            mBatteryPercentView.setText(percentText);
        }

        setContentDescription(
                getContext().getString(mCharging ? R.string.accessibility_battery_level_charging
                        : R.string.accessibility_battery_level, mLevel));
    }

    private void removeBatteryPercentView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
    }

    void updateShowPercent() {
        boolean drawPercentInside = mShowBatteryPercent == 1
                                    && !mCharging && !mBatteryStateUnknown;
        boolean showPercent = (mShowBatteryPercent == 2 && mBatteryStyle != BATTERY_STYLE_HIDDEN)
                                    || mBatteryStyle == BATTERY_STYLE_TEXT
                                    || (mBatteryPercentCharging && mCharging)
                                    || mShowPercentMode == MODE_ON
                                    || mShowPercentMode == MODE_ESTIMATE;
        showPercent = showPercent && !mBatteryStateUnknown;

        if (showPercent) {
            mThemedDrawable.setShowPercent(false);
            mCircleDrawable.setShowPercent(false);
            if (mBatteryPercentView == null) {
                mBatteryPercentView = loadPercentView();
                if (mPercentageStyleId != 0) { // Only set if specified as attribute
                    mBatteryPercentView.setTextAppearance(mPercentageStyleId);
                }
                if (mTextColor != 0) mBatteryPercentView.setTextColor(mTextColor);
                updatePercentText();
                addView(mBatteryPercentView,
                        new ViewGroup.LayoutParams(
                                LayoutParams.WRAP_CONTENT,
                                LayoutParams.MATCH_PARENT));
            }
            if (mBatteryStyle == BATTERY_STYLE_HIDDEN || mBatteryStyle == BATTERY_STYLE_TEXT) {
                mBatteryPercentView.setPaddingRelative(0, 0, 0, 0);
            } else {
                Resources res = getContext().getResources();
                mBatteryPercentView.setPaddingRelative(
                        res.getDimensionPixelSize(R.dimen.battery_level_padding_start), 0, 0, 0);
            }

        } else {
            removeBatteryPercentView();
            mCircleDrawable.setShowPercent(drawPercentInside);
            mThemedDrawable.setShowPercent(drawPercentInside);
        }
    }

    private void updateVisibility() {
        if (mBatteryStyle == BATTERY_STYLE_HIDDEN || mBatteryStyle == BATTERY_STYLE_TEXT) {
            mBatteryIconView.setVisibility(View.GONE);
            mBatteryIconView.setImageDrawable(null);
        } else {
            mBatteryIconView.setVisibility(View.VISIBLE);
            scaleBatteryMeterViews();
        }
    }

    private Drawable getUnknownStateDrawable() {
        if (mUnknownStateDrawable == null) {
            mUnknownStateDrawable = mContext.getDrawable(R.drawable.ic_battery_unknown);
            mUnknownStateDrawable.setTint(mTextColor);
        }

        return mUnknownStateDrawable;
    }

    void onBatteryUnknownStateChanged(boolean isUnknown) {
        if (mBatteryStateUnknown == isUnknown) {
            return;
        }

        mBatteryStateUnknown = isUnknown;

        if (mBatteryStateUnknown) {
            mBatteryIconView.setImageDrawable(getUnknownStateDrawable());
        } else {
            updateDrawable();
        }

        updateShowPercent();
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    void scaleBatteryMeterViews() {
        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight = mBatteryStyle == BATTERY_STYLE_CIRCLE || mBatteryStyle == BATTERY_STYLE_DOTTED_CIRCLE ?
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width) :
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
        int batteryWidth = mBatteryStyle == BATTERY_STYLE_CIRCLE || mBatteryStyle == BATTERY_STYLE_DOTTED_CIRCLE ?
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width) :
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);
        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                (int) (batteryWidth * iconScaleFactor), (int) (batteryHeight * iconScaleFactor));
        scaledLayoutParams.setMargins(0, 0, 0, marginBottom);

        mBatteryIconView.setLayoutParams(scaledLayoutParams);
    }

    private void updateDrawable() {
        switch (mBatteryStyle) {
            case BATTERY_STYLE_PORTRAIT:
                mBatteryIconView.setImageDrawable(mThemedDrawable);
                break;
            case BATTERY_STYLE_CIRCLE:
            case BATTERY_STYLE_DOTTED_CIRCLE:
                mCircleDrawable.setMeterStyle(mBatteryStyle);
                mBatteryIconView.setImageDrawable(mCircleDrawable);
                break;
            case BATTERY_STYLE_HIDDEN:
            case BATTERY_STYLE_TEXT:
                return;
            default:
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        float intensity = DarkIconDispatcher.isInAreas(areas, this) ? darkIntensity : 0;
        mNonAdaptedSingleToneColor = mDualToneHandler.getSingleColor(intensity);
        mNonAdaptedForegroundColor = mDualToneHandler.getFillColor(intensity);
        mNonAdaptedBackgroundColor = mDualToneHandler.getBackgroundColor(intensity);

        updateColors(mNonAdaptedForegroundColor, mNonAdaptedBackgroundColor,
                mNonAdaptedSingleToneColor);
    }

    /**
     * Sets icon and text colors. This will be overridden by {@code onDarkChanged} events,
     * if registered.
     *
     * @param foregroundColor
     * @param backgroundColor
     * @param singleToneColor
     */
    public void updateColors(int foregroundColor, int backgroundColor, int singleToneColor) {
        mCircleDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mThemedDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mTextColor = singleToneColor;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(singleToneColor);
        }

        if (mUnknownStateDrawable != null) {
            mUnknownStateDrawable.setTint(singleToneColor);
        }
    }

    public void dump(PrintWriter pw, String[] args) {
        String powerSave = mThemedDrawable == null ?
                null : mThemedDrawable.getPowerSaveEnabled() + "";
        CharSequence percent = mBatteryPercentView == null ? null : mBatteryPercentView.getText();
        pw.println("  BatteryMeterView:");
        pw.println("    getPowerSave: " + powerSave);
        pw.println("    mBatteryPercentView.getText(): " + percent);
        pw.println("    mTextColor: #" + Integer.toHexString(mTextColor));
        pw.println("    mBatteryStateUnknown: " + mBatteryStateUnknown);
        pw.println("    mLevel: " + mLevel);
        pw.println("    mMode: " + mShowPercentMode);
    }

    @VisibleForTesting
    CharSequence getBatteryPercentViewText() {
        return mBatteryPercentView.getText();
    }

    /** An interface that will fetch the estimated time remaining for the user's battery. */
    public interface BatteryEstimateFetcher {
        void fetchBatteryTimeRemainingEstimate(
                BatteryController.EstimateFetchCompletion completion);
    }
}

