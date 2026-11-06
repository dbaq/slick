package tv.slick;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** YouTube's TV banner (play button and wordmark on white), for the SmartTube tile. */
final class YouTubeBanner extends Drawable {

    private static final int RED = 0xFFFF0033;
    private static final int INK = 0xFF0F0F0F;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF button = new RectF();
    private final Path triangle = new Path();
    private final Rect textBounds = new Rect();

    YouTubeBanner() {
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        c.drawColor(0xFFFFFFFF);

        float bh = h * 0.30f;
        float bw = bh * 1.42f;
        paint.setTextSize(h * 0.30f);
        paint.setLetterSpacing(-0.04f);
        String word = "YouTube";
        paint.getTextBounds(word, 0, word.length(), textBounds);
        float gap = bh * 0.22f;
        float total = bw + gap + textBounds.width();
        float left = b.left + (w - total) / 2;
        float cy = b.top + h / 2;

        paint.setColor(RED);
        button.set(left, cy - bh / 2, left + bw, cy + bh / 2);
        c.drawRoundRect(button, bh * 0.28f, bh * 0.28f, paint);

        float tcx = button.centerX() + bh * 0.04f;
        float th = bh * 0.40f;
        triangle.reset();
        triangle.moveTo(tcx - th * 0.45f, cy - th / 2);
        triangle.lineTo(tcx + th * 0.55f, cy);
        triangle.lineTo(tcx - th * 0.45f, cy + th / 2);
        triangle.close();
        paint.setColor(0xFFFFFFFF);
        c.drawPath(triangle, paint);

        paint.setColor(INK);
        c.drawText(word, left + bw + gap - textBounds.left, cy - textBounds.exactCenterY(), paint);
    }

    @Override
    public ConstantState getConstantState() {
        return new ConstantState() {
            @Override
            public Drawable newDrawable() {
                return new YouTubeBanner();
            }

            @Override
            public int getChangingConfigurations() {
                return 0;
            }
        };
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
