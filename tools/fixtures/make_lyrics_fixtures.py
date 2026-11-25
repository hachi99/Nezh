"""
Audio fixtures with lyrics embedded in the file, for the Now Playing lyrics view.

Writes into app/src/androidTest/assets/lyrics/:
- lyrics_id3v23_utf16.mp3   USLT frame (UTF-16), Japanese + Spanish text
- lyrics_id3v24_utf8.mp3    USLT frame (UTF-8)
- lyrics_vorbis.flac        LYRICS Vorbis comment
- lyrics_ilst.m4a           iTunes ©lyr atom
- no_lyrics.mp3             same tags, no lyrics (empty state)
plus manifest.tsv: asset, expected lyrics with "\\n" escapes ("-" when none).

Pure Python, no external tools. Reuses the Unicode fixture builders.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from make_unicode_fixtures import flac_file, m4a, mp3_frame, frames_for_seconds, id3_tag, SECONDS  # noqa: E402

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "androidTest", "assets", "lyrics")

LYRICS = "君の声が聞こえる\n夜明けまで\n\nCanción de prueba — línea con acentos: ñ, ü, é\nЛюбовь · 사랑 · حب"
CASE = ("lyrics", "Letra incrustada", "Nezh Fixtures", "Pruebas de letra", True)


def mp3(version, encoding, lyrics):
    _, title, artist, album, cover = CASE
    audio = b"".join(mp3_frame(32) for _ in range(frames_for_seconds(SECONDS)))
    return id3_tag(version, encoding, title, artist, album, cover, lyrics) + audio


def flac(lyrics):
    _, title, artist, album, _ = CASE
    return flac_file(round(SECONDS / 0.0929), tags=[("TITLE", title), ("ARTIST", artist), ("ALBUM", album), ("LYRICS", lyrics)])


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    files = {
        "lyrics_id3v23_utf16.mp3": (mp3(3, 1, LYRICS), LYRICS),
        "lyrics_id3v24_utf8.mp3": (mp3(4, 3, LYRICS), LYRICS),
        "lyrics_vorbis.flac": (flac(LYRICS), LYRICS),
        "lyrics_ilst.m4a": (m4a(CASE, lyrics=LYRICS), LYRICS),
        "no_lyrics.mp3": (mp3(3, 1, None), None),
    }
    with open(os.path.join(OUT_DIR, "manifest.tsv"), "w", encoding="utf-8", newline="\n") as manifest:
        for name, (data, expected) in files.items():
            with open(os.path.join(OUT_DIR, name), "wb") as f:
                f.write(data)
            manifest.write(name + "\t" + (expected.replace("\n", "\\n") if expected else "-") + "\n")
    print(f"{len(files)} files in {os.path.normpath(OUT_DIR)}")


if __name__ == "__main__":
    main()
