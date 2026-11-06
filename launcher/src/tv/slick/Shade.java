package tv.slick;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * Darkens a backdrop where text and the dock sit (left, bottom, top). Baked into the bitmap once,
 * off the main thread, instead of three full-screen gradient views the GPU would blend every frame.
 */
final class Shade {

    static Bitmap apply(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, 0, 0, null);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w, 0,
                new int[] {0xD9000000, 0x4D000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, h, 0, 0,
                new int[] {0xF2000000, 0x40000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[] {0x99000000, 0x00000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return out;
    }
}
