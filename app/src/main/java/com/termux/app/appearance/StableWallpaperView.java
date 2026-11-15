package com.termux.app.appearance;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * An {@link ImageView} that never gets shorter once it has been measured tall.
 *
 * The terminal area shrinks when the on-screen keyboard opens, and a normal image view would
 * re-crop (shrink) the wallpaper to fit. This view keeps the tallest height it has been given
 * (the keyboard-down height) and lets its parent clip the part that falls behind the keyboard, so
 * the wallpaper stays one size. The remembered height is reset when the width changes, which
 * covers rotation.
 */
public class StableWallpaperView extends ImageView {

    private int mStableHeight = 0;
    private int mLastWidth = -1;

    public StableWallpaperView(Context context) {
        super(context);
    }

    public StableWallpaperView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public StableWallpaperView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int w = getMeasuredWidth();
        int h = getMeasuredHeight();

        if (w != mLastWidth) {
            mLastWidth = w;
            mStableHeight = 0;
        }
        if (h > mStableHeight) mStableHeight = h;

        setMeasuredDimension(w, mStableHeight);
    }
}
