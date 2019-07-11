import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.FloatRange;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.animation.PathInterpolatorCompat;

/**
* The Ripple Drawable implements an API < 21.
* API 28 based Ripple style.
*
* @author  SteaI
* @version 1.0
* @since   2019-07-11
*/
public class CompatRippleDrawable extends Drawable {
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();
    private static final TimeInterpolator DECELERATE_INTERPOLATOR =
            PathInterpolatorCompat.create(0.4f, 0f, 0.2f, 1f);

    private static final int RIPPLE_ENTER_DURATION = 225;

    private static final int OPACITY_ENTER_DURATION = 75;
    private static final int OPACITY_EXIT_DURATION = 150;
    private static final int OPACITY_HOLD_DURATION = OPACITY_ENTER_DURATION + 150;

    private static final int BACKGROUND_OPACITY_DURATION = 80;

    // state

    private RippleState mState;
    private boolean mMutated;

    // measure

    private float mActualCornerRadius;

    // ripple

    private boolean mRippleActive;

    private float mStartRadius;
    private float mTargetRadius;

    private boolean mFocused = false;
    private boolean mHovered = false;

    private final RectF mBounds = new RectF();
    private final RectF mCleanBounds = new RectF();
    private float mX;
    private float mY;

    private float mStartingX;
    private float mStartingY;
    private float mClampedStartingX;
    private float mClampedStartingY;

    private final Paint mPaint;

    private PorterDuffColorFilter mMaskColorFilter;
    private Bitmap mMaskBuffer;
    private BitmapShader mMaskShader;
    private Canvas mMaskCanvas;
    private Paint mMaskPaint;
    private Matrix mMaskMatrix;
    private Path mMaskPath;

    // animation

    private long mEnterStartedAtMillis;
    private float mRippleOpacity;
    private float mBackgroundOpacity;
    private float mTweenRadius;
    private float mTweenOrigin;

    private AnimatorSet rippleAnimatorSet;
    private ValueAnimator backgroundAnimator;

    CompatRippleDrawable(RippleState state) {
        mState = state;

        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.FILL);

        setColor(Color.argb(30, 0, 0, 0));
    }

    public CompatRippleDrawable() {
        this(new RippleState());
    }

    // region Property
    public int getColor() {
        return mState.mColor;
    }

    public void setColor(@ColorInt int color) {
        mState.mColor = color;

        mPaint.setColor(color);
        mState.mAlpha = Color.alpha(color);

        // update color filter
        if (mMaskColorFilter != null) {
            updateMaskFilter();
        }
    }

    public int getAlpha() {
        return mState.mAlpha;
    }

    @Override
    public void setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        mState.mAlpha = alpha;
    }

    public void setAlpha(@FloatRange(from = 0, to = 1) float alpha) {
        setAlpha((int) (255 * alpha));
    }

    public float getCornerRadius() {
        return mState.mCornerRadius;
    }

    public void setCornerRadius(@FloatRange(from = 0) float cornerRadius) {
        if (mState.mCornerRadius != cornerRadius) {
            mState.mCornerRadius = cornerRadius;
            measureCornerRadius();
            updateMaskShader();
        }
    }
    // endregion

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final boolean changed = super.onStateChange(stateSet);

        boolean enabled = false;
        boolean pressed = false;
        boolean focused = false;
        boolean hovered = false;

        for (int state : stateSet) {
            if (state == android.R.attr.state_enabled) {
                enabled = true;
            } else if (state == android.R.attr.state_focused) {
                focused = true;
            } else if (state == android.R.attr.state_pressed) {
                pressed = true;
            } else if (state == android.R.attr.state_hovered) {
                hovered = true;
            }
        }

        setRippleActive(enabled && pressed);
        setBackgroundActive(hovered, focused, pressed);

        return changed;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    private void setRippleActive(boolean active) {
        if (mRippleActive != active) {
            mRippleActive = active;
            if (active) {
                rippleEnter();
            } else {
                rippleExit();
            }
        }
    }

    private void setBackgroundActive(boolean hovered, boolean focused, boolean pressed) {
        if (!mFocused) {
            focused = focused && !pressed;
        }

        if (!mHovered) {
            hovered = hovered && !pressed;
        }

        if (mHovered != hovered || mFocused != focused) {
            mHovered = hovered;
            mFocused = focused;

            float opacity = mFocused ? 0.6f : mHovered ? 0.2f : 0f;
            animateBackground(opacity);
        }
    }

    private void cancelRipple() {
        if (rippleAnimatorSet != null) {
            rippleAnimatorSet.cancel();
            rippleAnimatorSet = null;
        }
    }

    private void rippleEnter() {
        mTweenRadius = 0;
        mTweenOrigin = 0;
        mRippleOpacity = 0;

        mStartingX = mX;
        mStartingY = mY;

        mStartRadius = Math.max(mBounds.width(), mBounds.height()) * 0.3f;

        mEnterStartedAtMillis = AnimationUtils.currentAnimationTimeMillis();

        clampStartingPosition();

        cancelRipple();

        ValueAnimator tweenAnimator = ValueAnimator.ofFloat(1);
        tweenAnimator.setDuration(RIPPLE_ENTER_DURATION);
        tweenAnimator.setInterpolator(DECELERATE_INTERPOLATOR);
        tweenAnimator.addUpdateListener(mTweenAnimation);

        ValueAnimator opacityAnimator = ValueAnimator.ofFloat(1);
        opacityAnimator.setDuration(OPACITY_ENTER_DURATION);
        opacityAnimator.setInterpolator(LINEAR_INTERPOLATOR);
        opacityAnimator.addUpdateListener(mRippleOpacityAnimation);

        rippleAnimatorSet = new AnimatorSet();
        rippleAnimatorSet.play(tweenAnimator).with(opacityAnimator);
        rippleAnimatorSet.start();
    }

    private void rippleExit() {
        if (mEnterStartedAtMillis == 0) {
            return;
        }

        long delay = computeFadeOutDelay();
        long duration = AnimationUtils.currentAnimationTimeMillis() - mEnterStartedAtMillis;
        float startTime = (float) (duration + delay);

        float startTween = DECELERATE_INTERPOLATOR.getInterpolation(
                Math.min(startTime / RIPPLE_ENTER_DURATION, 1));

        float startOpacity = LINEAR_INTERPOLATOR.getInterpolation(
                Math.min(startTime / OPACITY_ENTER_DURATION, 1));

        ValueAnimator tweenAnimator = ValueAnimator.ofFloat(startTween, 1);
        tweenAnimator.setDuration(OPACITY_EXIT_DURATION);
        tweenAnimator.setInterpolator(LINEAR_INTERPOLATOR);
        tweenAnimator.addUpdateListener(mTweenAnimation);

        ValueAnimator opacityAnimator = ValueAnimator.ofFloat(startOpacity, 0);
        opacityAnimator.setDuration(OPACITY_EXIT_DURATION);
        opacityAnimator.setInterpolator(LINEAR_INTERPOLATOR);
        opacityAnimator.addUpdateListener(mRippleOpacityAnimation);

        rippleAnimatorSet = new AnimatorSet();
        rippleAnimatorSet.setStartDelay(delay);
        rippleAnimatorSet.play(tweenAnimator).with(opacityAnimator);
        rippleAnimatorSet.start();
    }

    private void animateBackground(float opacity) {
        if (backgroundAnimator != null) {
            backgroundAnimator.cancel();
            backgroundAnimator = null;
        }

        backgroundAnimator = ValueAnimator.ofFloat(mBackgroundOpacity, opacity);
        backgroundAnimator.setDuration(BACKGROUND_OPACITY_DURATION);
        backgroundAnimator.setInterpolator(LINEAR_INTERPOLATOR);
        backgroundAnimator.addUpdateListener(mBackgroundOpacityAnimation);
        backgroundAnimator.start();
    }

    private long computeFadeOutDelay() {
        long timeSinceEnter = AnimationUtils.currentAnimationTimeMillis() - mEnterStartedAtMillis;
        if (timeSinceEnter > 0 && timeSinceEnter < OPACITY_HOLD_DURATION) {
            return OPACITY_HOLD_DURATION - timeSinceEnter;
        }
        return 0;
    }

    private void clampStartingPosition() {
        final float cX = mBounds.centerX();
        final float cY = mBounds.centerY();
        final float dX = mStartingX - cX;
        final float dY = mStartingY - cY;
        final float r = mTargetRadius - mStartRadius;

        if (dX * dX + dY * dY > r * r) {
            final double angle = Math.atan2(dY, dX);
            mClampedStartingX = cX + (float) (Math.cos(angle) * r);
            mClampedStartingY = cY + (float) (Math.sin(angle) * r);
        } else {
            mClampedStartingX = mStartingX;
            mClampedStartingY = mStartingY;
        }
    }

    private void measureCornerRadius() {
        float max = Math.min(mBounds.width(), mBounds.height()) / 2f;
        mActualCornerRadius = Math.min(max, mState.mCornerRadius);
    }

    private void updateMaskShader() {
        if (mState.mCornerRadius == 0 || mBounds.isEmpty()) {
            if (mMaskBuffer != null) {
                mMaskBuffer.recycle();
            }

            mMaskBuffer = null;
            mMaskShader = null;
            mMaskCanvas = null;
            mMaskColorFilter = null;
            mMaskMatrix = null;
            return;
        }

        if (mMaskBuffer == null ||
                mMaskBuffer.getWidth() != mBounds.width() ||
                mMaskBuffer.getHeight() != mBounds.height()) {
            if (mMaskBuffer != null) {
                mMaskBuffer.recycle();
            }

            mMaskBuffer = Bitmap.createBitmap(
                    (int) mBounds.width(), (int) mBounds.height(), Bitmap.Config.ALPHA_8);
            mMaskShader = new BitmapShader(mMaskBuffer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mMaskCanvas = new Canvas(mMaskBuffer);
        } else {
            mMaskBuffer.eraseColor(Color.TRANSPARENT);
        }

        if (mMaskMatrix == null) {
            mMaskMatrix = new Matrix();
        } else {
            mMaskMatrix.reset();
        }

        if (mMaskColorFilter == null) {
            updateMaskFilter();
        }

        if (mMaskPaint == null) {
            mMaskPaint = new Paint();
            mMaskPaint.setAntiAlias(true);
            mMaskPaint.setColor(Color.BLACK);
        }

        if (mMaskPath == null) {
            mMaskPath = new Path();
        } else {
            mMaskPath.rewind();
        }

        mMaskPath.addRoundRect(mCleanBounds, mActualCornerRadius, mActualCornerRadius, Path.Direction.CW);
        mMaskCanvas.drawPath(mMaskPath, mMaskPaint);
    }

    private void updateMaskFilter() {
        int color = (mState.mColor & 0xFFFFFF) | (255 << 24);
        mMaskColorFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private static float lerp(float start, float stop, float amount) {
        return start + (stop - start) * amount;
    }

    private float getCurrentX() {
        return lerp(mClampedStartingX - mBounds.centerX(), 0, mTweenOrigin);
    }

    private float getCurrentY() {
        return lerp(mClampedStartingY - mBounds.centerY(), 0, mTweenOrigin);
    }

    private float getCurrentRadius() {
        return lerp(mStartRadius, mTargetRadius, mTweenRadius);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        float cX = mBounds.width() / 2f;
        float cY = mBounds.height() / 2f;

        if (mMaskShader != null) {
            mPaint.setColorFilter(mMaskColorFilter);
            mPaint.setShader(mMaskShader);
            mMaskMatrix.setTranslate(-cX, -cY);
            mMaskShader.setLocalMatrix(mMaskMatrix);
        } else {
            mPaint.setColorFilter(null);
            mPaint.setShader(null);
        }

        cX += mBounds.left;
        cY += mBounds.top;

        int count = canvas.save();
        canvas.clipRect(mBounds);
        canvas.translate(cX, cY);

        drawBackground(canvas);
        drawRipple(canvas);

        canvas.restoreToCount(count);
    }

    private void drawBackground(Canvas canvas) {
        final int alpha = (int) (mState.mAlpha * mBackgroundOpacity + 0.5f);

        if (alpha > 0) {
            mPaint.setAlpha(alpha);
            canvas.drawCircle(0, 0, mTargetRadius, mPaint);
            mPaint.setAlpha(mState.mAlpha);
        }
    }

    private void drawRipple(Canvas canvas) {
        final int alpha = (int) (mState.mAlpha * mRippleOpacity + 0.5f);
        float radius = getCurrentRadius();

        if (alpha > 0 && radius > 0) {
            final float x = getCurrentX();
            final float y = getCurrentY();

            mPaint.setAlpha(alpha);
            canvas.drawCircle(x, y, radius, mPaint);
            mPaint.setAlpha(mState.mAlpha);
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);

        mBounds.set(bounds);
        mCleanBounds.left = 0;
        mCleanBounds.top = 0;
        mCleanBounds.right = bounds.width();
        mCleanBounds.bottom = bounds.height();

        measureCornerRadius();
        updateMaskShader();

        final float halfWidth = bounds.width() / 2.0f;
        final float halfHeight = bounds.height() / 2.0f;
        mTargetRadius = (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);

        invalidateSelf();
    }

    @Override
    public void setHotspot(float x, float y) {
        mX = x;
        mY = y;

        if (mRippleActive) {
            mStartingX = x;
            mStartingY = y;
            clampStartingPosition();
        }
    }

    @NonNull
    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mState = new RippleState(mState);
            mMutated = true;
        }
        return this;
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mState.getChangingConfigurations();
    }

    private final FloatAnimationListener mTweenAnimation = new FloatAnimationListener() {
        @Override
        protected void onAnimationUpdate(float value) {
            mTweenOrigin = value;
            mTweenRadius = value;
            invalidateSelf();
        }
    };

    private final FloatAnimationListener mRippleOpacityAnimation = new FloatAnimationListener() {
        @Override
        protected void onAnimationUpdate(float value) {
            mRippleOpacity = value;
            invalidateSelf();
        }
    };

    private final FloatAnimationListener mBackgroundOpacityAnimation = new FloatAnimationListener() {
        @Override
        protected void onAnimationUpdate(float value) {
            mBackgroundOpacity = value;
            invalidateSelf();
        }
    };

    private static abstract class FloatAnimationListener implements ValueAnimator.AnimatorUpdateListener {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            onAnimationUpdate((float) animation.getAnimatedValue());
        }

        protected abstract void onAnimationUpdate(float value);
    }

    final static class RippleState extends ConstantState {
        private int mChangingConfigurations;

        private float mCornerRadius;
        private int mColor;
        private int mAlpha;

        RippleState() {
        }

        RippleState(RippleState state) {
            mChangingConfigurations = state.mChangingConfigurations;
            mCornerRadius = state.mCornerRadius;
            mColor = state.mColor;
            mAlpha = state.mAlpha;
        }

        @NonNull
        @Override
        public Drawable newDrawable() {
            return new CompatRippleDrawable(this);
        }

        @NonNull
        @Override
        public Drawable newDrawable(@Nullable Resources res) {
            return new CompatRippleDrawable(this);
        }

        @NonNull
        @Override
        public Drawable newDrawable(@Nullable Resources res, @Nullable Resources.Theme theme) {
            return new CompatRippleDrawable(this);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outline.setAlpha(getAlpha() / 255f);
            outline.setRoundRect(getBounds(), mActualCornerRadius);
        } else {
            super.getOutline(outline);
        }
    }

    public static class Builder {
        private Float cornerRadius;
        private Integer color;
        private Integer alpha;

        public Builder setCornerRadius(@FloatRange(from = 0) float value) {
            cornerRadius = value;
            return this;
        }

        public Builder setColor(@ColorInt int value) {
            color = value;
            return this;
        }

        public Builder setAlpha(@IntRange(from = 0, to = 255) int value) {
            alpha = value;
            return this;
        }

        public Builder setAlpha(@FloatRange(from = 0, to = 1) float value) {
            alpha = (int) (255 * value);
            return this;
        }

        public CompatRippleDrawable build() {
            CompatRippleDrawable drawable = new CompatRippleDrawable();

            if (cornerRadius != null) {
                drawable.setCornerRadius(cornerRadius);
            }

            if (color != null) {
                drawable.setColor(color);
            }

            if (alpha != null) {
                drawable.setAlpha(alpha);
            }

            return drawable;
        }
    }
}
