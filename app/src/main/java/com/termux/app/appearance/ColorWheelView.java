package com.termux.app.appearance;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * A hue / saturation wheel. Brightness is not part of the wheel: set it with {@link #setBrightness}
 * (the dialog drives it from a slider), and the wheel darkens to show it.
 *
 * The state is kept as separate hue, saturation and brightness values rather than recomputed from the
 * resulting color, so the selector does not jump around when the brightness hits zero.
 */
public class ColorWheelView extends View {

    public interface Listener {
        void onColorChanged(int rgb);
    }

    private float mHue = 0f;
    private float mSaturation = 0f;
    private float mBrightness = 1f;

    private float mCx, mCy, mRadius;
    private final float mThumbRadius;

    private final Paint mHuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSaturationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbInner = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable
    private Listener mListener;

    public ColorWheelView(Context context) {
        this(context, null);
    }

    public ColorWheelView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ColorWheelView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        mThumbRadius = 11f * density;

        mThumbOuter.setStyle(Paint.Style.STROKE);
        mThumbOuter.setStrokeWidth(3f * density);
        mThumbOuter.setColor(Color.BLACK);
        mThumbInner.setStyle(Paint.Style.STROKE);
        mThumbInner.setStrokeWidth(1.5f * density);
        mThumbInner.setColor(Color.WHITE);
        mDimPaint.setStyle(Paint.Style.FILL);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /** The currently selected color as opaque 0xFFRRGGBB. */
    public int getColor() {
        return ColorMath.hsvToRgb(mHue, mSaturation, mBrightness);
    }

    /** Sets the whole color (hue, saturation and brightness) without notifying the listener. */
    public void setColor(int rgb) {
        float[] hsv = ColorMath.rgbToHsv(rgb);
        // Keep the old hue for greys and black, where the hue is undefined and would snap to red.
        if (hsv[1] > 0f && hsv[2] > 0f) mHue = hsv[0];
        mSaturation = hsv[1];
        mBrightness = hsv[2];
        invalidate();
    }

    public float getBrightness() {
        return mBrightness;
    }

    /** Sets brightness (0..1) and notifies the listener. */
    public void setBrightness(float brightness) {
        mBrightness = Math.max(0f, Math.min(1f, brightness));
        invalidate();
        notifyChanged();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int wMode = MeasureSpec.getMode(widthMeasureSpec);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        int preferred = Math.round(260 * density);

        int size;
        if (wMode == MeasureSpec.EXACTLY && hMode == MeasureSpec.EXACTLY) {
            size = Math.min(w, h);
        } else if (wMode == MeasureSpec.UNSPECIFIED && hMode == MeasureSpec.UNSPECIFIED) {
            size = preferred;
        } else {
            int limit = Integer.MAX_VALUE;
            if (wMode != MeasureSpec.UNSPECIFIED) limit = Math.min(limit, w);
            if (hMode != MeasureSpec.UNSPECIFIED) limit = Math.min(limit, h);
            size = Math.min(preferred, limit);
        }
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mCx = w / 2f;
        mCy = h / 2f;
        mRadius = Math.max(1f, Math.min(w, h) / 2f - mThumbRadius - 2f);

        int[] hues = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
        mHuePaint.setShader(new SweepGradient(mCx, mCy, hues, null));
        mSaturationPaint.setShader(new RadialGradient(mCx, mCy, mRadius, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawCircle(mCx, mCy, mRadius, mHuePaint);
        canvas.drawCircle(mCx, mCy, mRadius, mSaturationPaint);

        int dimAlpha = Math.round((1f - mBrightness) * 255f);
        if (dimAlpha > 0) {
            mDimPaint.setColor(Color.argb(dimAlpha, 0, 0, 0));
            canvas.drawCircle(mCx, mCy, mRadius, mDimPaint);
        }

        double angle = Math.toRadians(mHue);
        float tx = (float) (mCx + Math.cos(angle) * mSaturation * mRadius);
        float ty = (float) (mCy + Math.sin(angle) * mSaturation * mRadius);
        mThumbFill.setColor(getColor());
        canvas.drawCircle(tx, ty, mThumbRadius, mThumbFill);
        canvas.drawCircle(tx, ty, mThumbRadius, mThumbOuter);
        canvas.drawCircle(tx, ty, mThumbRadius, mThumbInner);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                updateFromTouch(event.getX(), event.getY());
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void updateFromTouch(float x, float y) {
        float dx = x - mCx;
        float dy = y - mCy;
        double distance = Math.sqrt(dx * dx + dy * dy);

        float hue = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (hue < 0f) hue += 360f;
        mHue = hue;
        mSaturation = (float) Math.min(1.0, distance / mRadius);
        invalidate();
        notifyChanged();
    }

    private void notifyChanged() {
        if (mListener != null) mListener.onColorChanged(getColor());
    }
}
