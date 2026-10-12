package org.jellyfin.androidtv.ui.playback;

/** Geometry for moving a caption into a player-generated lower letterbox bar. */
public final class LetterboxSubtitlePlacement {
    public final int top;
    public final int height;
    public final int bottomInset;

    private LetterboxSubtitlePlacement(int top, int height, int bottomInset) {
        this.top = top;
        this.height = height;
        this.bottomInset = bottomInset;
    }

    /**
     * Returns null when the fitted video has no lower bar, or the caption cannot fit at its current size.
     * Coordinates describe the actual video surface within the playback viewport, in pixels.
     */
    public static LetterboxSubtitlePlacement calculate(int width, int height, int videoLeft, int videoTop,
            int videoRight, int videoBottom, int captionHeight, int margin, int requestedBottomInset) {
        if (width <= 0 || height <= 0 || captionHeight <= 0 || margin < 0 || requestedBottomInset < 0) return null;
        if (videoLeft < 0 || videoTop <= 0 || videoRight <= videoLeft || videoBottom <= videoTop) return null;
        if (videoRight > width || videoBottom >= height) return null;

        // A fitted wide video spans the viewport width. Pillarboxing and smaller video windows do not qualify.
        if (videoLeft != 0 || videoRight != width) return null;
        int barHeight = height - videoBottom;
        if (Math.abs((long) videoTop - barHeight) > 2) return null;
        if (margin > barHeight / 2 || captionHeight > barHeight - margin * 2) return null;

        int maximumInset = barHeight - captionHeight - margin;
        int inset = Math.max(margin, Math.min(requestedBottomInset, maximumInset));
        return new LetterboxSubtitlePlacement(videoBottom, barHeight, inset);
    }
}
