package org.jellyfin.androidtv.ui.playback;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;

import timber.log.Timber;

/**
 * Samples the video surface with PixelCopy (Android 7+) and reports black bars that are encoded into the video
 * itself. Copies that fail, such as for protected or tunneled output, leave no bars known.
 * <p>
 * On some devices a copy blocks its calling thread for hundreds of milliseconds, so copies run on a background
 * thread, read only a centre strip of the frame, and are spaced out further when they are slow.
 */
public final class EncodedBarDetector {
    private static final int SAMPLE_WIDTH = 96;
    private static final int SAMPLE_HEIGHT = 270;
    private static final long MIN_INTERVAL_MS = 1000;
    private static final long MAX_INTERVAL_MS = 10000;
    /** Once bars are measured, copies may take at most 1/COST_FACTOR of the time. */
    private static final int COST_FACTOR = 20;
    /** Copies made at the minimum interval first, so bars are found quickly. */
    private static final int QUICK_COPIES = 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("EncodedBarDetector");
    private final Handler copyHandler;
    private final EncodedBarScan scan = new EncodedBarScan();
    private final View surface;
    private final Runnable onChange;
    private final int[] pixels = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private final int[] luma = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
    private final Rect source = new Rect();
    private Bitmap bitmap;
    private boolean running;
    private boolean copying;
    private long interval = MIN_INTERVAL_MS;
    private long copyStarted;
    private int copies;

    private final Runnable sample = this::sample;

    public EncodedBarDetector(View surface, Runnable onChange) {
        this.surface = surface;
        this.onChange = onChange;
        thread.start();
        copyHandler = new Handler(thread.getLooper());
    }

    public static boolean isSupported(View surface) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && surface instanceof SurfaceView;
    }

    public void start() {
        if (running || !isSupported(surface)) return;
        running = true;
        if (bitmap == null) bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888);
        mainHandler.postDelayed(sample, interval);
    }

    public void stop() {
        running = false;
        mainHandler.removeCallbacks(sample);
    }

    /** Forget the current crop, for example when the next item starts. */
    public void reset() {
        scan.reset();
        copies = 0;
        interval = MIN_INTERVAL_MS;
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
        mainHandler.postDelayed(sample, interval);
        if (copying || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        SurfaceView view = (SurfaceView) surface;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0 || !view.getHolder().getSurface().isValid()) return;
        // Bars span the full width, so a centre strip is enough and cheaper to copy.
        source.set(width * 3 / 8, 0, width * 5 / 8, height);
        copying = true;
        copyHandler.post(() -> request(view));
    }

    /** Runs on the copy thread. */
    private void request(SurfaceView view) {
        copyStarted = SystemClock.elapsedRealtime();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PixelCopy.request(view, source, bitmap, this::onCopied, copyHandler);
            } else {
                PixelCopy.request(view, bitmap, this::onCopied, copyHandler);
            }
        } catch (IllegalArgumentException e) {
            mainHandler.post(() -> copying = false);
        }
    }

    /** Runs on the copy thread. */
    private void onCopied(int result) {
        long cost = SystemClock.elapsedRealtime() - copyStarted;
        boolean copied = result == PixelCopy.SUCCESS;
        if (copied) {
            bitmap.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                luma[i] = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
            }
        }
        mainHandler.post(() -> {
            long next = ++copies <= QUICK_COPIES ? MIN_INTERVAL_MS
                    : Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, cost * COST_FACTOR));
            if (next != interval) Timber.d("Encoded bar copy took %d ms, sampling every %d ms", cost, next);
            interval = next;
            // luma is only rewritten by the next copy, which waits for this flag.
            if (running && copied && scan.update(luma, SAMPLE_WIDTH, SAMPLE_HEIGHT)) onChange.run();
            copying = false;
        });
    }

    public void release() {
        // The bitmap is not recycled: a copy may still be writing into it.
        stop();
        thread.quitSafely();
    }
}
