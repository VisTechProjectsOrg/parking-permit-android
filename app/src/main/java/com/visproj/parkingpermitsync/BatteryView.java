package com.visproj.parkingpermitsync;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

// Horizontal battery outline that empties as the display's battery drains, with an optional
// charging bolt. Percent < 0 draws an empty grey outline (no reading yet).
public class BatteryView extends View {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path boltPath = new Path();
    private int percent = -1;
    private boolean charging = false;

    public BatteryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f * d);
        outline.setColor(Color.parseColor("#e2e8f0"));
        bolt.setColor(Color.parseColor("#fdd835"));
        bolt.setStyle(Paint.Style.FILL);
    }

    public void setLevel(int percent, boolean charging) {
        this.percent = percent;
        this.charging = charging;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float nub = 2.5f * d, stroke = outline.getStrokeWidth();
        float bodyW = w - nub - stroke;

        int outlineColor = Color.parseColor(percent < 0 ? "#8892a6" : "#e2e8f0");
        outline.setColor(outlineColor);
        nubPaint.setColor(outlineColor);
        rect.set(stroke / 2, stroke / 2, bodyW, h - stroke / 2);
        canvas.drawRoundRect(rect, 2.5f * d, 2.5f * d, outline);
        canvas.drawRect(bodyW, h * 0.3f, w, h * 0.7f, nubPaint);

        if (percent > 0) {
            fill.setColor(Color.parseColor(percent <= 20 ? "#f44336" : percent <= 40 ? "#ff9800" : "#4caf50"));
            float inset = stroke + 1.5f * d;
            float maxW = bodyW - inset * 2;
            rect.set(inset, inset, inset + maxW * Math.min(percent, 100) / 100f, h - inset);
            canvas.drawRoundRect(rect, 1.2f * d, 1.2f * d, fill);
        }

        if (charging) {
            float cx = bodyW / 2, top = h * 0.12f, bot = h * 0.88f, bw = h * 0.32f;
            boltPath.reset();
            boltPath.moveTo(cx + bw * 0.25f, top);
            boltPath.lineTo(cx - bw * 0.55f, h * 0.56f);
            boltPath.lineTo(cx - bw * 0.02f, h * 0.56f);
            boltPath.lineTo(cx - bw * 0.25f, bot);
            boltPath.lineTo(cx + bw * 0.55f, h * 0.44f);
            boltPath.lineTo(cx + bw * 0.02f, h * 0.44f);
            boltPath.close();
            canvas.drawPath(boltPath, bolt);
        }
    }

}
