package org.jellyfin.androidtv.ui.playback;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import java.util.Collections;

/** Debug-only local-media fixture. OK toggles the same overlay used by the real Jellyfin video player. */
@OptIn(markerClass = UnstableApi.class)
public final class SubtitlePlacementTestActivity extends Activity {
    private static final float TEXT_SIZE_FRACTION = 0.0533f;
    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout root;
    private LetterboxSubtitleOverlay overlay;
    private boolean enabled;
    private CaptionStyleCompat style;
    private float textSizeFraction;
    private float offsetFraction;
    private View.OnLayoutChangeListener safetyListener;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        style = new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.DEFAULT);
        playerView.getSubtitleView().setStyle(style);
        textSizeFraction = TEXT_SIZE_FRACTION * Math.max(25, Math.min(250, getIntent().getIntExtra("text_scale", 100))) / 100f;
        offsetFraction = getIntent().getIntExtra("offset_percent", 0) / 100f;
        playerView.getSubtitleView().setFractionalTextSize(textSizeFraction);
        if (getIntent().getBooleanExtra("raw_padding", false)) {
            playerView.getSubtitleView().setBottomPaddingFraction(offsetFraction);
        } else {
            safetyListener = SubtitleViewSafety.apply(playerView.getSubtitleView(), offsetFraction);
        }
        enabled = getIntent().getBooleanExtra("letterbox", false);
        applyOverlay();

        String video = getIntent().getBooleanExtra("full_frame", false) ? "subtitle-test-full.mp4" : "subtitle-test-wide.mp4";
        if (getIntent().getBooleanExtra("narrow_bar", false)) video = "subtitle-test-narrow.mp4";
        if (getIntent().getBooleanExtra("encoded_bars", false)) video = "subtitle-test-encoded.mkv";
        String subtitleFile = getIntent().getBooleanExtra("descenders", false) ? "subtitle-test-descenders.srt" : "subtitle-test.srt";
        MediaItem.SubtitleConfiguration subtitles = new MediaItem.SubtitleConfiguration.Builder(Uri.parse("asset:///" + subtitleFile))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();
        // Embedded-track fixtures (encoded bars): PGS images or native ASS with default, \pos and \an8 lines.
        boolean embedded = getIntent().getBooleanExtra("pgs", false) || getIntent().getBooleanExtra("ass", false);
        if (getIntent().getBooleanExtra("pgs", false)) video = "subtitle-test-pgs.mkv";
        if (getIntent().getBooleanExtra("ass", false)) video = "subtitle-test-ass.mkv";
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguage("en").build());
        player.setMediaItem(new MediaItem.Builder().setUri("asset:///" + video)
                .setSubtitleConfigurations(embedded ? Collections.emptyList() : Collections.singletonList(subtitles)).build());
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.seekTo(getIntent().getIntExtra("position_ms", 0));
        player.prepare();
    }

    private void applyOverlay() {
        if (overlay != null) {
            overlay.release();
            overlay = null;
        }
        if (enabled) {
            overlay = new LetterboxSubtitleOverlay(this, playerView, style, textSizeFraction, offsetFraction);
            root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) {
                enabled = !enabled;
                applyOverlay();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        player.play();
    }

    @Override
    protected void onPause() {
        player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (overlay != null) overlay.release();
        if (safetyListener != null) playerView.getSubtitleView().removeOnLayoutChangeListener(safetyListener);
        playerView.setPlayer(null);
        player.release();
        super.onDestroy();
    }
}
