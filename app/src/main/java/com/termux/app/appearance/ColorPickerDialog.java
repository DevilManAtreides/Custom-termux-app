package com.termux.app.appearance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.R;

/**
 * Color picker with a hue/saturation wheel, a brightness slider and a hex field. The wheel, slider
 * and hex field all stay in sync, and typing a valid {@code #rrggbb} moves the wheel to it.
 */
public final class ColorPickerDialog {

    public interface Callback {
        void onColorSelected(int rgb);
    }

    private ColorPickerDialog() {}

    public static void show(@NonNull Context context, @NonNull CharSequence title, @Nullable CharSequence description,
                            int initialRgb, @NonNull final Callback callback) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = Math.round(20 * density);

        final int[] current = {initialRgb | 0xFF000000};
        // True while one control is updating the others, so they do not echo changes back at each other.
        final boolean[] updating = {false};

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, Math.round(8 * density), pad, 0);

        if (description != null && description.length() > 0) {
            TextView desc = new TextView(context);
            desc.setText(description);
            desc.setPadding(0, 0, 0, Math.round(8 * density));
            content.addView(desc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        final ColorWheelView wheel = new ColorWheelView(context);
        LinearLayout.LayoutParams wheelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(260 * density));
        content.addView(wheel, wheelParams);

        TextView brightnessLabel = new TextView(context);
        brightnessLabel.setText(R.string.termux_appearance_picker_brightness);
        brightnessLabel.setPadding(0, Math.round(8 * density), 0, 0);
        content.addView(brightnessLabel);

        final SeekBar brightness = new SeekBar(context);
        brightness.setMax(100);
        content.addView(brightness, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(8 * density), 0, Math.round(8 * density));

        final View swatch = new View(context);
        final GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setShape(GradientDrawable.RECTANGLE);
        swatchBg.setCornerRadius(8 * density);
        swatchBg.setStroke(Math.max(1, Math.round(density)), 0x66888888);
        swatch.setBackground(swatchBg);
        LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(Math.round(48 * density), Math.round(48 * density));
        swatchParams.rightMargin = Math.round(16 * density);
        row.addView(swatch, swatchParams);

        final EditText hex = new EditText(context);
        hex.setSingleLine(true);
        hex.setHint("#rrggbb");
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        row.addView(hex, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(row);

        final ColorStateList normalHexColor = hex.getTextColors();

        // Push the current color into every control except the one named in 'source'.
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                swatchBg.setColor(current[0]);
                swatch.invalidate();
                ColorStateList tint = ColorStateList.valueOf(current[0]);
                brightness.setProgressTintList(tint);
                brightness.setThumbTintList(tint);
            }
        };

        wheel.setListener(new ColorWheelView.Listener() {
            @Override
            public void onColorChanged(int rgb) {
                if (updating[0]) return;
                updating[0] = true;
                current[0] = rgb;
                hex.setText(ColorMath.toHex(rgb));
                hex.setTextColor(normalHexColor);
                refresh.run();
                updating[0] = false;
            }
        });

        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updating[0]) return;
                wheel.setBrightness(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (seekBar.getParent() != null) seekBar.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                Integer parsed = ColorMath.parseHex(s.toString());
                if (parsed == null) {
                    // Partial or invalid input: keep the last good color, just flag the field.
                    hex.setTextColor(Color.RED);
                    return;
                }
                hex.setTextColor(normalHexColor);
                updating[0] = true;
                current[0] = parsed;
                wheel.setColor(parsed);
                brightness.setProgress(Math.round(wheel.getBrightness() * 100f));
                refresh.run();
                updating[0] = false;
            }
        });

        // Initial state.
        updating[0] = true;
        wheel.setColor(current[0]);
        brightness.setProgress(Math.round(wheel.getBrightness() * 100f));
        hex.setText(ColorMath.toHex(current[0]));
        refresh.run();
        updating[0] = false;

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);

        new AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> callback.onColorSelected(current[0]))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
