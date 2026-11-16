package com.termux.app.appearance;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small fake terminal that shows how the current appearance settings look together: the
 * wallpaper with its blur and dim, the font, the foreground, background and cursor, all 16
 * ANSI colors, and how see-through colored cells are.
 *
 * It is drawn on a canvas instead of using the real terminal, so fonts and colors are accurate
 * but spacing can differ slightly. Call {@link #refresh()} after any setting changes.
 */
public class AppearancePreviewView extends View {

    private static final int COLS = 40;
    private static final int ROWS = 13;
    /** Same working size the real wallpaper is blurred at, so the blur looks proportionally the same. */
    private static final int BLUR_WORKING_DIMENSION = 720;

    private static final int FOREGROUND = -1;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint();
    private final Paint mImagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();

    /** foreground, background, cursor, then color0 to color15 (see AppearanceManager.getColorKeys). */
    private int[] mColors = AppearanceManager.getEffectiveColors();
    private boolean mWallpaperOn;
    private int mDimAlpha;
    private int mCellAlpha = 255;
    private Typeface mTypeface = Typeface.MONOSPACE;
    private long mTypefaceStamp = -1;

    // Main thread only: what is currently drawn.
    private Bitmap mShown;
    private long mShownStamp = -1;
    private int mShownBlur = -1;

    // Background thread only: the wallpaper scaled down once, so blur changes do not re-decode the file.
    private Bitmap mSmall;
    private long mSmallStamp = -1;

    private volatile int mGeneration = 0;

    public AppearancePreviewView(Context context) {
        this(context, null);
    }

    public AppearancePreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setContentDescription(context.getString(R.string.termux_appearance_preview_description));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }

    /** Re-reads every appearance setting and redraws. Cheap enough to call on every slider tick. */
    public void refresh() {
        final Context app = getContext().getApplicationContext();
        SharedPreferences prefs = AppearanceManager.getPrefs(app);

        mWallpaperOn = AppearanceManager.isWallpaperEnabled(app);
        int blur = clamp(prefs.getInt(AppearanceManager.KEY_WALLPAPER_BLUR, 0), 0, AppearanceManager.MAX_BLUR);
        mDimAlpha = ColorMath.percentToAlpha(clamp(prefs.getInt(AppearanceManager.KEY_WALLPAPER_DIM, 30), 0, AppearanceManager.MAX_DIM));
        // Colored cells are only see-through while a wallpaper is on, exactly as in the real terminal.
        mCellAlpha = mWallpaperOn
            ? ColorMath.percentToAlpha(prefs.getInt(AppearanceManager.KEY_CELL_OPACITY, 100))
            : 255;
        mColors = AppearanceManager.getEffectiveColors();
        updateTypeface();

        if (mWallpaperOn) {
            loadWallpaper(app, blur);
        } else {
            mGeneration++;
            mShown = null;
            mShownStamp = -1;
            mShownBlur = -1;
        }
        invalidate();
    }

    private void updateTypeface() {
        if (!AppearanceManager.hasCustomFont()) {
            mTypeface = Typeface.MONOSPACE;
            mTypefaceStamp = -1;
            return;
        }
        File font = TermuxConstants.TERMUX_FONT_FILE;
        long stamp = font.lastModified();
        if (stamp == mTypefaceStamp) return;
        try {
            mTypeface = Typeface.createFromFile(font);
        } catch (Exception e) {
            mTypeface = Typeface.MONOSPACE;
        }
        mTypefaceStamp = stamp;
    }

    private void loadWallpaper(Context app, final int blur) {
        final File file = AppearanceManager.getWallpaperFile(app);
        final long stamp = file.lastModified();
        if (mShown != null && stamp == mShownStamp && blur == mShownBlur) return;

        final int generation = ++mGeneration;
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // A newer request came in while this one waited: skip the work.
                if (generation != mGeneration) return;
                try {
                    if (mSmall == null || stamp != mSmallStamp) {
                        Bitmap src = BitmapFactory.decodeFile(file.getAbsolutePath());
                        if (src == null) return;
                        float scale = Math.min(1f, BLUR_WORKING_DIMENSION / (float) Math.max(src.getWidth(), src.getHeight()));
                        Bitmap small = src;
                        if (scale < 1f) {
                            small = Bitmap.createScaledBitmap(src,
                                Math.max(1, Math.round(src.getWidth() * scale)),
                                Math.max(1, Math.round(src.getHeight() * scale)), true);
                            if (small != src) src.recycle();
                        }
                        mSmall = small;
                        mSmallStamp = stamp;
                    }
                    if (generation != mGeneration) return;
                    final Bitmap result = blur > 0 ? BlurUtils.stackBlur(mSmall, blur) : mSmall;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (generation != mGeneration) return;
                            mShown = result;
                            mShownStamp = stamp;
                            mShownBlur = blur;
                            invalidate();
                        }
                    });
                } catch (Throwable ignored) {
                    // Out of memory or a bad image: keep showing whatever we had.
                }
            }
        });
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float density = getResources().getDisplayMetrics().density;

        // Background: the wallpaper with the dim applied, otherwise the plain terminal background.
        if (mWallpaperOn && mShown != null) {
            drawCenterCrop(canvas, mShown, w, h);
            if (mDimAlpha > 0) {
                mFillPaint.setColor(Color.argb(mDimAlpha, 0, 0, 0));
                canvas.drawRect(0, 0, w, h, mFillPaint);
            }
        } else {
            canvas.drawColor(mColors[1] | 0xFF000000);
        }

        // Pick the text size so all columns and rows fit, whatever the shape of the preview.
        final float pad = 10 * density;
        mTextPaint.setTypeface(mTypeface);
        mTextPaint.setTextSize(100f);
        float fontWidth100 = mTextPaint.measureText("M");
        float lineHeight100 = mTextPaint.getFontSpacing();
        if (fontWidth100 <= 0f || lineHeight100 <= 0f) return;
        float size = Math.min(
            100f * (w - 2 * pad) / (COLS * fontWidth100),
            100f * (h - 2 * pad) / (ROWS * lineHeight100));
        if (size < 4f) return;
        mTextPaint.setTextSize(size);

        final float cellW = mTextPaint.measureText("M");
        final float cellH = mTextPaint.getFontSpacing();
        final float ascent = -mTextPaint.ascent();
        final Grid grid = new Grid(canvas, pad, pad, cellW, cellH, ascent);

        // 0: prompt
        grid.text(0, 0, "~ $ ls --color", FOREGROUND);
        // 1: a file listing, colored the way ls does it
        int c = grid.text(1, 0, "docs/", 12);
        c = grid.text(1, c, "  ", FOREGROUND);
        c = grid.text(1, c, "run.sh", 10);
        c = grid.text(1, c, "  ", FOREGROUND);
        c = grid.text(1, c, "pkg.tar", 9);
        c = grid.text(1, c, "  ", FOREGROUND);
        c = grid.text(1, c, "link", 14);
        c = grid.text(1, c, "  ", FOREGROUND);
        grid.text(1, c, "notes.txt", FOREGROUND);
        // 2 to 5: diff-style lines
        grid.text(2, 0, "~ $ git diff", FOREGROUND);
        grid.text(3, 0, "+ added line", 2);
        grid.text(4, 0, "- removed line", 1);
        grid.text(5, 0, "! warning: check this", 3);
        // 6: editor-style colors
        c = grid.text(6, 0, "info", 4);
        c = grid.text(6, c, " ", FOREGROUND);
        c = grid.text(6, c, "keyword", 5);
        c = grid.text(6, c, " ", FOREGROUND);
        c = grid.text(6, c, "string", 6);
        c = grid.text(6, c, " ", FOREGROUND);
        grid.text(6, c, "# comment", 8);
        // 7 and 8: all 16 colors, as cell backgrounds so the opacity setting is visible on them
        for (int i = 0; i < 16; i++) {
            int row = 7 + i / 8;
            int col = (i % 8) * 5;
            int color = mColors[3 + i] | 0xFF000000;
            grid.fill(row, col, 5, withAlpha(color, mCellAlpha));
            grid.textColor(row, col + 1, "c" + i, ColorMath.readableOn(color));
        }
        // 9: a colored bar, like htop's highlighted row. Follows the opacity setting.
        int bar = mColors[3 + 4] | 0xFF000000;
        grid.fill(9, 0, COLS, withAlpha(bar, mCellAlpha));
        grid.textColor(9, 1, "colored bar (follows opacity)", ColorMath.readableOn(bar));
        // 10: selected text. The real terminal draws a selection as the foreground color with the
        // text in the background color, and always solid, so the opacity setting does not apply.
        grid.fill(10, 0, COLS, mColors[0] | 0xFF000000);
        grid.textColor(10, 1, "selected text (always solid)", mColors[1] | 0xFF000000);
        // 11: glyph test for the font
        grid.text(11, 0, "The quick brown fox 0O 1lI {}[]<>", FOREGROUND);
        // 12: prompt with the cursor
        grid.text(12, 0, "~ $ vim", FOREGROUND);
        grid.fill(12, 7, 1, mColors[2] | 0xFF000000);
    }

    private int withAlpha(int rgb, int alpha) {
        return (rgb & 0x00FFFFFF) | (alpha << 24);
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, float w, float h) {
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float scale = Math.max(w / bw, h / bh);
        float srcW = w / scale;
        float srcH = h / scale;
        float left = (bw - srcW) / 2f;
        float top = (bh - srcH) / 2f;
        mSrc.set(Math.round(left), Math.round(top), Math.round(left + srcW), Math.round(top + srcH));
        mDst.set(0, 0, w, h);
        canvas.drawBitmap(bitmap, mSrc, mDst, mImagePaint);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Draws on a character grid: row and column in, pixels out. */
    private final class Grid {
        private final Canvas mCanvas;
        private final float mLeft, mTop, mCellW, mCellH, mAscent;

        Grid(Canvas canvas, float left, float top, float cellW, float cellH, float ascent) {
            mCanvas = canvas;
            mLeft = left;
            mTop = top;
            mCellW = cellW;
            mCellH = cellH;
            mAscent = ascent;
        }

        /** Draws text in a palette color (0 to 15), or the foreground color for {@link #FOREGROUND}. Returns the next column. */
        int text(int row, int col, String s, int paletteIndex) {
            int color = paletteIndex == FOREGROUND ? mColors[0] : mColors[3 + paletteIndex];
            return textColor(row, col, s, color | 0xFF000000);
        }

        int textColor(int row, int col, String s, int argb) {
            mTextPaint.setColor(argb);
            mCanvas.drawText(s, mLeft + col * mCellW, mTop + row * mCellH + mAscent, mTextPaint);
            return col + s.length();
        }

        void fill(int row, int col, int cols, int argb) {
            mFillPaint.setColor(argb);
            float x = mLeft + col * mCellW;
            float y = mTop + row * mCellH;
            mCanvas.drawRect(x, y, x + cols * mCellW, y + mCellH, mFillPaint);
        }
    }
}
