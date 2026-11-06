package tv.slick;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Line icons for the glass buttons, drawn rather than shipped as assets. */
final class GlyphDrawable extends Drawable {

    enum Kind { WIFI, GEAR }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path path = new Path();
    private final Path tooth = new Path();
    private final Path hole = new Path();
    private final Matrix rotation = new Matrix();
    /** Wi-Fi bars lit, 0 to 3; {@link #OFFLINE} draws the icon struck through. */
    private int level = 3;

    static final int OFFLINE = -1;

    GlyphDrawable(Kind kind) {
        this.kind = kind;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    void setBars(int level) {
        this.level = level;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        float s = getBounds().width();
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        paint.setStrokeWidth(s * 0.085f);
        if (kind == Kind.WIFI) {
            float base = cy + s * 0.30f;
            int full = paint.getAlpha();
            for (int i = 1; i <= 3; i++) {
                float r = s * 0.18f * i;
                oval.set(cx - r, base - r, cx + r, base + r);
                paint.setAlpha(level >= i ? full : full * 3 / 10);
                c.drawArc(oval, 225, 90, false, paint);
            }
            paint.setAlpha(full);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, base - s * 0.02f, s * 0.07f, paint);
            paint.setStyle(Paint.Style.STROKE);
            if (level == OFFLINE) c.drawLine(cx - s * 0.36f, cy - s * 0.36f, cx + s * 0.36f, cy + s * 0.36f, paint);
        } else {
            // A filled cog: body, eight square teeth, and a hole punched through the middle.
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addCircle(cx, cy, s * 0.30f, Path.Direction.CW);
            for (int i = 0; i < 8; i++) {
                tooth.reset();
                tooth.addRect(cx - s * 0.075f, cy - s * 0.44f, cx + s * 0.075f, cy - s * 0.26f, Path.Direction.CW);
                rotation.setRotate(45 * i, cx, cy);
                tooth.transform(rotation);
                path.op(tooth, Path.Op.UNION);
            }
            hole.reset();
            hole.addCircle(cx, cy, s * 0.12f, Path.Direction.CW);
            path.op(hole, Path.Op.DIFFERENCE);
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
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
        return PixelFormat.TRANSLUCENT;
    }
}
