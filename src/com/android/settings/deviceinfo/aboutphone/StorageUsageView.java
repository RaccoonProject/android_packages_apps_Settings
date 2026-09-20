/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A storage usage bar that fills sideways with a solid color.
 *
 * <p>The fill level animates from empty to the target progress on first display. Colors are set
 * from the Material theme so the bar always matches light/dark mode.
 */
public class StorageUsageView extends View {

    private static final long PROGRESS_DURATION_MS = 1100;
    private static final float CORNER_RADIUS_DP = 20f;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClipPath = new Path();

    private final float mCornerRadiusPx;

    private float mProgress;
    private float mTargetProgress;
    private ValueAnimator mProgressAnimator;

    public StorageUsageView(Context context) {
        this(context, null /* attrs */);
    }

    public StorageUsageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0 /* defStyleAttr */);
    }

    public StorageUsageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        final float density = context.getResources().getDisplayMetrics().density;
        mCornerRadiusPx = CORNER_RADIUS_DP * density;
        setColors(Color.DKGRAY /* trackColor */, Color.GRAY /* fillColor */);
    }

    /** Sets the bar colors. */
    public void setColors(int trackColor, int fillColor) {
        mTrackPaint.setColor(trackColor);
        mFillPaint.setColor(fillColor);
        invalidate();
    }

    /** Animates the fill level to {@code progress} (0..1). Safe to call before attach. */
    public void setProgress(float progress) {
        mTargetProgress = Math.max(0f, Math.min(1f, progress));
        animateProgress();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animateProgress();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mProgressAnimator != null) {
            mProgressAnimator.cancel();
            mProgressAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mClipPath.rewind();
        mClipPath.addRoundRect(0, 0, w, h, mCornerRadiusPx, mCornerRadiusPx,
                Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float width = getWidth();
        final float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        canvas.drawRoundRect(0, 0, width, height, mCornerRadiusPx, mCornerRadiusPx,
                mTrackPaint);
        if (mProgress <= 0f) {
            return;
        }
        canvas.save();
        canvas.clipPath(mClipPath);
        canvas.drawRect(0, 0, width * mProgress, height, mFillPaint);
        canvas.restore();
    }

    private void animateProgress() {
        if (!isAttachedToWindow()) {
            // onAttachedToWindow() will pick up mTargetProgress.
            return;
        }
        if (mProgressAnimator != null) {
            mProgressAnimator.cancel();
        }
        mProgressAnimator = ValueAnimator.ofFloat(mProgress, mTargetProgress);
        mProgressAnimator.setDuration(PROGRESS_DURATION_MS);
        mProgressAnimator.setInterpolator(new DecelerateInterpolator());
        mProgressAnimator.addUpdateListener(
                animation -> {
                    mProgress = (float) animation.getAnimatedValue();
                    invalidate();
                });
        mProgressAnimator.start();
    }
}
