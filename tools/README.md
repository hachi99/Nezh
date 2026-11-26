# Nezh test tools

Reproducible checks for audio format support and library performance. Nothing here ships in the app.

## 1. Format compatibility matrix

Fixtures: `tools/fixtures/make_audio_fixtures.py` (Python standard library only, deterministic)
writes `app/src/androidTest/assets/formats/`. Regenerate with:

```
python tools/fixtures/make_audio_fixtures.py
```

| Fixture | Built by | Purpose |
|---|---|---|
| `mp3_cbr_tagged.mp3` | script | ID3v2.3 title/artist/album + 600×600 cover, 20 s CBR |
| `mp3_cbr_untagged.mp3` | script | no tags: file-name title fallback |
| `mp3_partial_tags.mp3` | script | artist only |
| `mp3_vbr_xing.mp3` | script | VBR (32 → 320 kbps) with Xing header + TOC |
| `mp3_vbr_noheader.mp3` | script | same VBR stream without Xing/VBRI header |
| `mp3_large_artwork.mp3` | script | 3000×3000 cover (memory) |
| `flac_tagged.flac` / `flac_untagged.flac` | script | Vorbis comments + PICTURE / none |
| `wav_pcm16.wav` | script | PCM 16-bit |
| `unsupported.mid`, `corrupt.mp3` | script | must fail as FILE errors, not crash |
| `m4a_aac.m4a`, `aac_adts.aac`, `opus.ogg` | device, at test time | Android MediaCodec encoders |
| `alac_16_44k_stereo.m4a`, `alac_24_96k_stereo.m4a`, `alac_16_48k_mono.m4a` | encoded elsewhere (below) | compressed ALAC: 16/24 bit, mono/stereo, last frame partial |
| `alac_bad_prediction.m4a`, `../codecs/tone_alac_20bit.m4a` | `make_alac_variant_fixtures.py` | ALAC the Java decoder must report as an error / as unsupported |

MP3 and FLAC fixtures contain digital silence (valid frames without audio data); that is enough for
container, decoder, duration, seek and metadata checks, but not for audio-quality judgements.
No Ogg Vorbis fixture: neither Android nor this script can encode it. To cover it, add real files
encoded elsewhere to the assets and to `FormatCompatibilityTest`.

The three compressed ALAC files were encoded with FFmpeg 6.0.1's ALAC encoder from deterministic
WAV files (two tones plus seeded noise, 4 s 16-bit 44.1 kHz stereo, 3 s 24-bit 96 kHz stereo, 3 s
16-bit 48 kHz mono). The SHA-256 of that source PCM is in `AlacPacketDecoderTest`, which checks
that the Java decoder reproduces it bit for bit. No file encoded by Apple software is included.

Run on any device (emulator or the Galaxy A54 with USB debugging):

```
gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hachicorp.nezh.playback.FormatCompatibilityTest
adb logcat -d -s NezhFormatMatrix
```

The log lists the device's decoders per MIME type and one row per fixture: playable, decoder,
error, duration vs expected, seekable, 50 % seek, time to end after seeking to the last 2 s,
full playback at 8×, decoder initialisations, title/artist/album, artwork size.
The test fails only for essential formats (MP3, AAC/M4A, FLAC on API 27+), wrong titles, or
unsupported/corrupt files that do not fail cleanly. It reads only its own fixtures.

## 2. Library scan performance

`LibraryScanBenchmarkTest` scans a synthetic tree served by `BenchTreeProvider`, a provider that
exists only in the test APK and runs in its own process (real cross-process queries). It creates
**no files**, so it is safe on a personal phone:

```
gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hachicorp.nezh.library.LibraryScanBenchmarkTest
adb logcat -d -s NezhPerfBench
```

It reports, for 100 / 1,000 / 5,000 / 10,000 tracks: SAF queries, walk and sort time, retained
heap, and the main-thread cost of merge, queue filter, MediaItem building and state comparison;
plus walk time by parallelism.

`realProviderParallelism` measures the real storage provider. **Emulator only**: create files with
`tools/library-bench/generate_library.sh` (see the header of that script; it has a `clean` mode),
authorize the `NezhBench` folder in Nezh, then run the class above. It is skipped when no
`NezhBench` folder is authorized.

In the app, scans and queue operations log counts and durations (never names or URIs):

```
adb logcat -s NezhPerf
```
