package org.jellyfin.androidtv.ui.playback;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.util.Collections;
import java.util.List;

/**
 * AI-assisted prototype using public Media3 APIs. The video renderer keeps its own positioned and bitmap cues.
 * Ordinary unpositioned captions use a sibling SubtitleView only while they fit inside a measured lower bar.
 * See https://developer.android.com/reference/androidx/media3/ui/SubtitleView and Media3 1.8 SubtitlePainter.
 */
@OptIn(markerClass = UnstableApi.class)
public final class LetterboxSubtitleOverlay extends FrameLayout {
    private static final float INNER_TEXT_PADDING = 0.125f;

    private final PlayerView playerView;
    private final Player player;
    private final SubtitleView originalSubtitles;
    private final SubtitleView barSubtitles;
    private final View videoSurface;
    private final float textSizeFraction;
    private final float offsetFraction;
    private final TextPaint textPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final float spacingMultiplier;
    private final float spacingExtra;
    private final int margin;
    private final int[] viewportLocation = new int[2];
    private final int[] videoLocation = new int[2];
    private final int[] playerLocation = new int[2];
    private List<Cue> cues = Collections.emptyList();

    private final OnLayoutChangeListener layoutListener = (view, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom) -> requestLayout();

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onCues(@NonNull CueGroup cueGroup) {
            cues = cueGroup.cues;
            updatePlacement();
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
            requestLayout();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            // Never carry a previous item's captions across a playlist or aspect-ratio transition.
            cues = Collections.emptyList();
            restoreOriginalSubtitles();
        }
    };

    public LetterboxSubtitleOverlay(Context context, PlayerView playerView, CaptionStyleCompat style,
            float textSizeFraction, float offsetFraction) {
        super(context);
        this.playerView = playerView;
        this.player = playerView.getPlayer();
        this.originalSubtitles = playerView.getSubtitleView();
        this.videoSurface = playerView.getVideoSurfaceView();
        this.textSizeFraction = textSizeFraction;
        this.offsetFraction = offsetFraction;
        margin = Math.round(SubtitleViewSafety.MINIMUM_BOTTOM_MARGIN_DP * getResources().getDisplayMetrics().density);
        textPaint.setTypeface(style.typeface);

        TypedArray attributes = context.obtainStyledAttributes(null,
                new int[] {android.R.attr.lineSpacingExtra, android.R.attr.lineSpacingMultiplier}, 0, 0);
        spacingExtra = attributes.getDimensionPixelSize(0, 0);
        spacingMultiplier = attributes.getFloat(1, 1);
        attributes.recycle();

        barSubtitles = new SubtitleView(context);
        barSubtitles.setStyle(style);
        addView(barSubtitles, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setVisibility(INVISIBLE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        playerView.addOnLayoutChangeListener(layoutListener);
        if (videoSurface != null) videoSurface.addOnLayoutChangeListener(layoutListener);
        player.addListener(listener);
        cues = player.getCurrentCues().cues;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updatePlacement();
    }

    private void updatePlacement() {
        if (originalSubtitles == null || videoSurface == null || cues.size() != 1
                || !canMove(cues.get(0)) || playerView.getResizeMode() != AspectRatioFrameLayout.RESIZE_MODE_FIT
                || player.getVideoSize().height <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            restoreOriginalSubtitles();
            return;
        }

        getLocationInWindow(viewportLocation);
        playerView.getLocationInWindow(playerLocation);
        // Guide/PiP contraction uses a smaller video viewport; leave those captions with the existing player.
        if (playerView.getWidth() != getWidth() || playerView.getHeight() != getHeight()
                || playerLocation[0] != viewportLocation[0] || playerLocation[1] != viewportLocation[1]) {
            restoreOriginalSubtitles();
            return;
        }

        videoSurface.getLocationInWindow(videoLocation);
        int videoLeft = videoLocation[0] - viewportLocation[0];
        int videoTop = videoLocation[1] - viewportLocation[1];
        float textSize = textSizeFraction
                * (originalSubtitles.getHeight() - originalSubtitles.getPaddingTop() - originalSubtitles.getPaddingBottom());
        if (!Float.isFinite(textSize) || textSize <= 0 || !Float.isFinite(offsetFraction)) {
            restoreOriginalSubtitles();
            return;
        }

        int textHeight = measureCaptionHeight(cues.get(0), textSize);
        LetterboxSubtitlePlacement placement = LetterboxSubtitlePlacement.calculate(getWidth(), getHeight(),
                videoLeft, videoTop, videoLeft + videoSurface.getWidth(), videoTop + videoSurface.getHeight(),
                textHeight, margin, Math.round(Math.max(0, offsetFraction) * getHeight()));
        if (placement == null) {
            restoreOriginalSubtitles();
            return;
        }

        // Keep the existing readable font size; sizing it relative to the thin bar would make the text tiny.
        barSubtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        barSubtitles.setBottomPaddingFraction((float) placement.bottomInset / placement.height);
        // SubtitleView contains its own drawing child; both must be measured against the smaller bar.
        barSubtitles.measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(placement.height, MeasureSpec.EXACTLY));
        barSubtitles.layout(0, placement.top, getWidth(), placement.top + placement.height);
        barSubtitles.setCues(cues);
        originalSubtitles.setVisibility(INVISIBLE);
        setVisibility(VISIBLE);
    }

    private static boolean canMove(Cue cue) {
        // Explicit cue coordinates must stay in their original video coordinate system (e.g. ASS and PGS).
        return cue.text != null && cue.text.length() > 0 && cue.bitmap == null && cue.line == Cue.DIMEN_UNSET
                && cue.verticalType == Cue.TYPE_UNSET && cue.textSize == Cue.DIMEN_UNSET
                && (cue.size == Cue.DIMEN_UNSET || cue.size == 1f)
                && (cue.position == Cue.DIMEN_UNSET || cue.position == 0.5f);
    }

    @SuppressWarnings("deprecation")
    private int measureCaptionHeight(Cue cue, float textSize) {
        // Match Media3's canvas text wrapping, including a second layout at the measured text width.
        // The legacy StaticLayout constructor also supports this app's Android 5 minimum SDK.
        textPaint.setTextSize(textSize);
        int padding = (int) (textSize * INNER_TEXT_PADDING + 0.5f);
        int width = getWidth() - padding * 2;
        if (width <= 0) return -1;
        Layout.Alignment alignment = cue.textAlignment != null ? cue.textAlignment : Layout.Alignment.ALIGN_CENTER;
        StaticLayout layout = new StaticLayout(cue.text, textPaint, width, alignment, spacingMultiplier, spacingExtra, true);
        if (cue.size == Cue.DIMEN_UNSET) {
            int textWidth = 0;
            for (int line = 0; line < layout.getLineCount(); line++) {
                textWidth = Math.max(textWidth, (int) Math.ceil(layout.getLineWidth(line)));
            }
            width = Math.min(getWidth(), textWidth + padding * 2);
            if (width <= 0) return -1;
            layout = new StaticLayout(cue.text, textPaint, width, alignment, spacingMultiplier, spacingExtra, true);
        }
        return layout.getHeight();
    }

    private void restoreOriginalSubtitles() {
        barSubtitles.setCues(null);
        if (originalSubtitles != null) originalSubtitles.setVisibility(VISIBLE);
        setVisibility(INVISIBLE);
    }

    /** Removes callbacks before releasing the player, including callbacks retaining the old activity. */
    public void release() {
        player.removeListener(listener);
        playerView.removeOnLayoutChangeListener(layoutListener);
        if (videoSurface != null) videoSurface.removeOnLayoutChangeListener(layoutListener);
        cues = Collections.emptyList();
        restoreOriginalSubtitles();
        if (getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(this);
    }
}
