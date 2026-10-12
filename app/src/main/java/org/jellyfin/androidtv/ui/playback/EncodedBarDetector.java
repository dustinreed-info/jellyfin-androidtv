package org.jellyfin.androidtv.ui.playback;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;

/**
 * Samples the video surface about once a second with PixelCopy (Android 7+) and reports black bars that are
 * encoded into the video itself. Copies that fail, such as for protected or tunneled output, leave no bars known.
 */
public final class EncodedBarDetector {
    private static final int SAMPLE_WIDTH = 96;
    private static final int SAMPLE_HEIGHT = 270;
    private static final long INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EncodedBarScan scan = new EncodedBarScan();
    private final View surface;
    private final Runnable onChange;
    private final int[] pixels = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private final int[] luma = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private Bitmap bitmap;
    private boolean running;
    private boolean copying;

    private final Runnable sample = this::sample;

    public EncodedBarDetector(View surface, Runnable onChange) {
        this.surface = surface;
        this.onChange = onChange;
    }

    public static boolean isSupported(View surface) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && surface instanceof SurfaceView;
    }

    public void start() {
        if (running || !isSupported(surface)) return;
        running = true;
        if (bitmap == null) bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);
        handler.postDelayed(sample, INTERVAL_MS);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(sample);
    }

    /** Forget the current crop, for example when the next item starts. */
    public void reset() {
        scan.reset();
        onChange.run();
    }

    public float topFraction() {
        return scan.topFraction();
    }

    public float bottomFraction() {
        return scan.bottomFraction();
    }

    private void sample() {
        if (!running) return;
        handler.postDelayed(sample, INTERVAL_MS);
        if (copying || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        SurfaceView view = (SurfaceView) surface;
        if (view.getWidth() <= 0 || view.getHeight() <= 0 || !view.getHolder().getSurface().isValid()) return;
        copying = true;
        try {
            PixelCopy.request(view, bitmap, this::onCopied, handler);
        } catch (IllegalArgumentException e) {
            copying = false;
        }
    }

    private void onCopied(int result) {
        copying = false;
        if (!running || result != PixelCopy.SUCCESS) return;
        bitmap.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            luma[i] = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
        }
        if (scan.update(luma, SAMPLE_WIDTH, SAMPLE_HEIGHT)) onChange.run();
    }

    public void release() {
        // Not recycled: a copy may still be writing into it.
        stop();
    }
}
