package tv.slick;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Icons for the glass buttons and the Home panel, drawn rather than shipped as assets. */
final class GlyphDrawable extends Drawable {

    enum Kind { SEARCH, WIFI, GEAR, HOUSE, BULB, SPEAKER, FLAME, SNOW, SPARK }

    private Kind kind;
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
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    void setKind(Kind kind) {
        if (this.kind == kind) return;
        this.kind = kind;
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
        if (kind == Kind.SEARCH) {
            // A magnifier: lens up and to the left, handle to the bottom right.
            float r = s * 0.24f;
            float lx = cx - s * 0.07f;
            float ly = cy - s * 0.07f;
            c.drawCircle(lx, ly, r, paint);
            float edge = r * 0.7071f;
            c.drawLine(lx + edge, ly + edge, cx + s * 0.34f, cy + s * 0.34f, paint);
        } else if (kind == Kind.HOUSE) {
            path.reset();
            path.moveTo(cx - s * 0.38f, cy - s * 0.02f);
            path.lineTo(cx, cy - s * 0.36f);
            path.lineTo(cx + s * 0.38f, cy - s * 0.02f);
            path.moveTo(cx - s * 0.26f, cy - s * 0.12f);
            path.lineTo(cx - s * 0.26f, cy + s * 0.32f);
            path.lineTo(cx + s * 0.26f, cy + s * 0.32f);
            path.lineTo(cx + s * 0.26f, cy - s * 0.12f);
            c.drawPath(path, paint);
        } else if (kind == Kind.BULB) {
            // A solid bulb: round glass narrowing into the neck, then the screw base below a gap.
            path.reset();
            path.addCircle(cx, cy - s * 0.1f, s * 0.27f, Path.Direction.CW);
            tooth.reset();
            tooth.moveTo(cx - s * 0.2f, cy + s * 0.04f);
            tooth.lineTo(cx + s * 0.2f, cy + s * 0.04f);
            tooth.lineTo(cx + s * 0.12f, cy + s * 0.22f);
            tooth.lineTo(cx - s * 0.12f, cy + s * 0.22f);
            tooth.close();
            path.op(tooth, Path.Op.UNION);
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(path, paint);
            oval.set(cx - s * 0.12f, cy + s * 0.27f, cx + s * 0.12f, cy + s * 0.4f);
            c.drawRoundRect(oval, s * 0.05f, s * 0.05f, paint);
            paint.setStyle(Paint.Style.STROKE);
        } else if (kind == Kind.SPEAKER) {
            path.reset();
            path.moveTo(cx - s * 0.36f, cy - s * 0.11f);
            path.lineTo(cx - s * 0.2f, cy - s * 0.11f);
            path.lineTo(cx, cy - s * 0.3f);
            path.lineTo(cx, cy + s * 0.3f);
            path.lineTo(cx - s * 0.2f, cy + s * 0.11f);
            path.lineTo(cx - s * 0.36f, cy + s * 0.11f);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            for (float r : new float[] {s * 0.16f, s * 0.32f}) {
                oval.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(oval, -45, 90, false, paint);
            }
        } else if (kind == Kind.FLAME) {
            // A solid flame leaning a little, with a small tongue on the side.
            path.reset();
            path.moveTo(cx + s * 0.02f, cy - s * 0.42f);
            path.cubicTo(cx + s * 0.36f, cy - s * 0.1f, cx + s * 0.32f, cy + s * 0.4f, cx, cy + s * 0.4f);
            path.cubicTo(cx - s * 0.32f, cy + s * 0.4f, cx - s * 0.34f, cy + s * 0.02f, cx - s * 0.16f, cy - s * 0.14f);
            path.cubicTo(cx - s * 0.14f, cy - s * 0.02f, cx - s * 0.08f, cy + s * 0.02f, cx - s * 0.04f, cy + s * 0.02f);
            path.cubicTo(cx - s * 0.08f, cy - s * 0.16f, cx - s * 0.06f, cy - s * 0.3f, cx + s * 0.02f, cy - s * 0.42f);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
        } else if (kind == Kind.SNOW) {
            // Three crossing strokes with small chevrons at each end.
            for (int i = 0; i < 3; i++) {
                c.save();
                c.rotate(60 * i, cx, cy);
                c.drawLine(cx, cy - s * 0.38f, cx, cy + s * 0.38f, paint);
                for (int end : new int[] {-1, 1}) {
                    float y = cy + end * s * 0.26f;
                    c.drawLine(cx, y, cx - s * 0.09f, y + end * s * 0.1f, paint);
                    c.drawLine(cx, y, cx + s * 0.09f, y + end * s * 0.1f, paint);
                }
                c.restore();
            }
        } else if (kind == Kind.SPARK) {
            // A four-point sparkle with curved sides.
            path.reset();
            path.moveTo(cx, cy - s * 0.42f);
            path.quadTo(cx + s * 0.05f, cy - s * 0.05f, cx + s * 0.42f, cy);
            path.quadTo(cx + s * 0.05f, cy + s * 0.05f, cx, cy + s * 0.42f);
            path.quadTo(cx - s * 0.05f, cy + s * 0.05f, cx - s * 0.42f, cy);
            path.quadTo(cx - s * 0.05f, cy - s * 0.05f, cx, cy - s * 0.42f);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
        } else if (kind == Kind.WIFI) {
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
