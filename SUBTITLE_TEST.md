# Fire TV subtitle placement test

Local AI-assisted prototype based on Jellyfin Android TV **v0.19.9**,
commit `14a5e160efbb33d471ef2efe7ed9073fc2206785`.
Current test version: **0.19.9-subtitles.4**, labeled **Jellyfin Subtitle Test**.
Application ID: `org.jellyfin.androidtv.debug`.

## Try the updated build

Open **Jellyfin Subtitle Test**. The existing server and login are retained by updating the same app in place.

For full-resolution movies that contain black bars within their encoded frames, use an **SRT** track,
**Fit** scaling, and **Preferences → Playback → Subtitles → Offset from bottom = 0%**.
Zero offset now keeps an eight dp safety margin for the bottoms of letters and their outline.
The font size stays unchanged.

For cropped widescreen files whose bars are added by the player, also enable
**Prefer subtitles in black bars**. This setting defaults to off and requires restarting playback
when changed. A separate subtitle view places the measured caption in the lower bar.

If the bar is too shallow for the current caption/font size (for example three lines in a 2.39:1 bar),
the caption's bottom lines stay in the bar and only the remaining lines extend upward into the picture.
Captions taller than half the screen stay with the existing renderer. Image subtitles, explicitly positioned text, multiple simultaneous cues, stretched
or zoomed viewports, and contracted video retain their existing coordinate system. Automatic detection
of bars encoded into the video is not implemented. Styled ASS rendering is not changed by this patch.
The separate experimental player backend is not changed.

## Zero-offset safety fix

The safety margin applies to the existing video subtitle renderer as well as the new bar overlay.
It is recomputed when the viewport changes shape. It uses Media3's bottom-padding API, which affects
unpositioned text without altering the coordinate system of bitmap and deliberately positioned cues.

On the Fire Stick (AFTMM, Android API 25), a generated **3840 × 2160 HEVC/MKV** fixture with encoded bars
and a two-line SRT caption containing `g`, `y`, `p`, `q`, and `j` produced these results:

| Padding | Caption rows in 4K capture | Clearance below visible text |
| --- | --- | --- |
| Previous literal 0% | 1908–2151 | 8 px |
| Updated 0% with safety margin | 1876–2119 | 40 px |
| 8% reference | 1736–1979 | 180 px |

All three captured the same 244 px glyph-block height. Normalized white glyph masks differed by
one pixel between the safe zero-offset and eight-percent reference captures (>99.99% agreement).
The old zero-offset fixture also had complete glyphs in the software capture; the owner's exact
physical/file-specific clipping was not reproduced. The added margin provides more room at the edge,
and the owner's two real movies still need a repeat check.

## Tall-caption check (subtitles.4)

On the Fire Stick with the wide 2.39:1 fixture, a four-line caption previously rendered entirely
inside the picture (rows 1458–1838, none in the bar). With **Prefer subtitles in black bars** it now
renders at rows 1740–2120: 204 of its 323 text rows are in the lower bar (which starts at row 1878),
with 40 px clearance above the screen edge. A two-line caption still fits fully in the bar.
Captures: `docs/subtitle-test-v4/`.

## Verification

**28 unit tests pass**, including nine letterbox placement tests and six safety-margin tests.
Eight new device captures cover encoded HEVC bars, player-generated wide bars, narrower bars,
0% and 8% offsets, and complete bottom-line descenders. Four required normalized-glyph comparisons
passed with >99.98% agreement. Initial fixtures also verified explicitly positioned SRT text,
tall-caption fallback, full-frame video, and switching back to original placement.

These fixtures verify software rendering on this Fire Stick. HDR/Dolby Vision output, styled ASS,
other devices, and the physical Ambilight result are outside this verification.

The installed update used the same signing certificate. The saved authentication store and subtitle
preferences were byte-identical immediately after `adb install -r`. Normal session startup later
refreshes cached server timestamps; the server, user, and access token remain present.

## Future updates without re-entering server details

Keep the same application ID and signing identity, and update using `adb install -r`.
The persistent signing key has a private backup outside the source archive.
The local helper `/home/whocares/.local/share/jellyfin-subtitle-test/build-update.py` builds and tests,
checks the package ID and pinned signing certificate, and performs an in-place update only when
invoked with `--install`. It aborts on identity mismatches rather than replacing the app's data.
It also verifies private-data hashes immediately after installation without printing credentials.

## Rebuild or run the fixture

Use JDK 21 and Android SDK platform 36:

```sh
./gradlew --no-daemon --max-workers=4 -Pjellyfin.version=0.19.9-subtitles.4 \
  :app:testDebugUnitTest :app:assembleDebug
```

To launch the local HEVC/descender fixture on the selected ADB device:

```sh
adb shell am start -W -S \
  -n org.jellyfin.androidtv.debug/org.jellyfin.androidtv.ui.playback.SubtitlePlacementTestActivity \
  --ez descenders true --ez encoded_bars true --ei offset_percent 0 --ei position_ms 10000
```

The automated demo was stopped after verification.

Verified APK SHA-256:

```text
bcf86878c0ae8adacd1849ca3b232f424f2821b328670417c26cdb56931d301c
```

The implementation uses public [Media3 SubtitleView APIs](https://developer.android.com/reference/androidx/media3/ui/SubtitleView).
Caption measurement follows the canvas wrapping rules in
[Media3 1.8.0 SubtitlePainter](https://github.com/androidx/media/blob/1.8.0/libraries/ui/src/main/java/androidx/media3/ui/SubtitlePainter.java).
The existing issue is [Jellyfin Android TV #4234](https://github.com/jellyfin/jellyfin-androidtv/issues/4234).
