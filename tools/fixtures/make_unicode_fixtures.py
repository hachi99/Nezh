"""Generates the Unicode playback fixtures (androidTest assets/unicode) and their manifest.

Pure Python standard library, deterministic output. Reuses the silent MP3 frames, FLAC frames and
PNG covers of make_audio_fixtures.py, and builds a minimal M4A (AAC-LC silence) with iTunes tags.

Each case combines title / artist / album in different scripts, and is written in several tag
encodings real files use:
  - MP3, ID3v2.3, UTF-16 with BOM (encoding 1)      - most Windows taggers, iTunes
  - MP3, ID3v2.3, ISO-8859-1 (encoding 0)            - older taggers; only for text that fits
  - MP3, ID3v2.4, UTF-8 (encoding 3)                 - foobar2000, MusicBrainz Picard
  - MP3, ID3v2.4, UTF-16BE without BOM (encoding 2)
  - FLAC, Vorbis comments (always UTF-8)
  - M4A, iTunes ilst atoms (UTF-8)
plus, for Japanese text, the non-standard but common Shift-JIS bytes declared as ISO-8859-1
(encoding 0): the text cannot be decoded correctly, but the file must still play.

Document paths (folders and file names) are Unicode too. The manifest (manifest.tsv) lists, per
file: asset, document path, expected title, artist, album, whether those are exact (UTF-8, one row
per line, tab-separated), and whether a cover is embedded.

Usage:  python tools/fixtures/make_unicode_fixtures.py
"""
import os
import struct
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(__file__))
from make_audio_fixtures import flac_file, frames_for_seconds, mp3_frame, png  # noqa: E402

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "androidTest", "assets", "unicode")

# (key, title, artist, album, cover)
CASES = [
    ("en_ja_artist", "Morning Light", "山田太郎", "Seasons", True),
    ("en_ja_album", "Evening Road", "Kaito", "夏の終わり", False),
    ("ja_title_en_artist", "さくらの歌", "The Blossoms", "Spring", False),
    ("ru_title_en_artist", "Ночной город", "Northline", "Lights", False),
    ("en_title_ru_artist", "Silver Line", "Земфира Петрова", "Roads", False),
    ("zh_zh", "夜空中最亮的星", "星光乐队", "夜晚", False),
    ("ko_ko", "봄날의 기억", "하늘밴드", "계절", False),
    ("ar_ar", "أغنية الليل", "فرقة القمر", "ليالي", False),
    ("mixed", "Café ☕ — Ελλάδα", "Björk & 坂本", "Ру́сский / عربي", True),
    ("diacritics", "Ñandú Zoë", unicodedata.normalize("NFD", "Dvořák"), "Ångström", False),
    ("symbols", "👩‍🎤 Live! (Remix) #1 ♪", "Z̤͔ͧ̑̓ä", "∞ / 🎶 ☆", False),
    # Western European text only (every character <= U+00FF): also written as ID3 Latin-1 below.
    ("latin_accents", "Canción de él", "Beyoncé", "Où est la fête", True),
]

COVER = png(64, 64, (40, 90, 160))
SECONDS = 4


# --- MP3 / ID3 ------------------------------------------------------------------------------

def synchsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def encode_text(text, encoding):
    if encoding == 0:  # ISO-8859-1, as declared
        return b"\x00" + text.encode("latin-1")
    if encoding == -1:  # Shift-JIS bytes mislabelled as ISO-8859-1 (non-standard, common)
        return b"\x00" + text.encode("shift_jis")
    if encoding == 1:  # UTF-16 with BOM
        return b"\x01" + text.encode("utf-16")
    if encoding == 2:  # UTF-16BE, no BOM
        return b"\x02" + text.encode("utf-16-be")
    return b"\x03" + text.encode("utf-8")


def id3_frame(version, fid, body):
    size = synchsafe(len(body)) if version == 4 else struct.pack(">I", len(body))
    return fid.encode("ascii") + size + b"\x00\x00" + body


def id3_tag(version, encoding, title, artist, album, cover, lyrics=None):
    frames = [
        id3_frame(version, "TIT2", encode_text(title, encoding)),
        id3_frame(version, "TPE1", encode_text(artist, encoding)),
        id3_frame(version, "TALB", encode_text(album, encoding)),
    ]
    if cover:
        frames.append(id3_frame(version, "APIC", b"\x00image/png\x00\x03\x00" + COVER))
    if lyrics:
        # USLT: encoding byte, language, empty content descriptor (terminated), text.
        terminator = b"\x00\x00" if encoding in (1, 2) else b"\x00"
        text = encode_text(lyrics, encoding)
        frames.append(id3_frame(version, "USLT", text[:1] + b"jpn" + terminator + text[1:]))
    body = b"".join(frames)
    return b"ID3" + bytes([version, 0, 0]) + synchsafe(len(body)) + body


def mp3(version, encoding, case):
    _, title, artist, album, cover = case
    audio = b"".join(mp3_frame(32) for _ in range(frames_for_seconds(SECONDS)))
    return id3_tag(version, encoding, title, artist, album, cover) + audio


def unsynchronised(tag):
    """ID3v2.3 tag-level unsynchronisation (header flag 0x80): a 0x00 after every 0xFF that is
    followed by 0xE0 or more, or by 0x00, as older taggers (e.g. Windows Media Player) write it."""
    body = tag[10:]
    out = bytearray()
    for i, b in enumerate(body):
        out.append(b)
        if b == 0xFF and (i + 1 == len(body) or body[i + 1] >= 0xE0 or body[i + 1] == 0x00):
            out.append(0x00)
    return tag[:5] + bytes([0x80]) + synchsafe(len(out)) + bytes(out)


def ape_v2_and_id3v1(title, artist, album):
    """APEv2 tag (UTF-8, with header and footer) followed by a 128-byte ID3v1 tag, at the end."""
    items = b""
    for key, value in (("Title", title), ("Artist", artist), ("Album", album)):
        v = value.encode("utf-8")
        items += struct.pack("<II", len(v), 0) + key.encode("ascii") + b"\x00" + v
    size = len(items) + 32  # items + footer

    def header(flags):
        return b"APETAGEX" + struct.pack("<IIII", 2000, size, 3, flags) + bytes(8)

    ape = header(0xA0000000) + items + header(0x80000000)

    def field(text, n):
        return text.encode("latin-1", "replace")[:n].ljust(n, b"\x00")

    id3v1 = b"TAG" + field(title, 30) + field(artist, 30) + field(album, 30) + b"2024" + bytes(30) + bytes([255])
    return ape + id3v1


def big_png(side):
    """A noisy (incompressible) PNG of side x side pixels: several MB, like a high-resolution cover."""
    import random
    import zlib
    rnd = random.Random(side)
    raw = b"".join(b"\x00" + bytes(rnd.getrandbits(8) for _ in range(side * 3)) for _ in range(side))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", side, side, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 1)) + chunk(b"IEND", b"")


def stress_files():
    """Real-world tag shapes that most often break players, with a Japanese artist."""
    case = ("stress", "Morning Light", "山田太郎", "Seasons", True)
    _, title, artist, album, _ = case
    audio = b"".join(mp3_frame(32) for _ in range(frames_for_seconds(SECONDS)))
    files = {}
    files["stress_id3v23_utf16_unsync.mp3"] = unsynchronised(id3_tag(3, 1, title, artist, album, True)) + audio
    files["stress_id3v24_utf8_ape_id3v1.mp3"] = id3_tag(4, 3, title, artist, album, False) + audio + ape_v2_and_id3v1(title, artist, album)
    cover = big_png(1200)
    frames = [id3_frame(3, "TIT2", encode_text(title, 1)), id3_frame(3, "TPE1", encode_text(artist, 1)),
              id3_frame(3, "TALB", encode_text(album, 1)), id3_frame(3, "APIC", b"\x00image/png\x00\x03\x00" + cover)]
    body = b"".join(frames)
    files["stress_id3v23_utf16_bigcover.mp3"] = b"ID3\x03\x00\x00" + synchsafe(len(body)) + body + audio
    files["stress_bigcover.flac"] = flac_file(round(SECONDS / 0.0929), tags=[("TITLE", title), ("ARTIST", artist), ("ALBUM", album)], picture=cover)
    return case, files


# --- M4A (AAC-LC silence + iTunes ilst) ------------------------------------------------------

RATE, FRAME = 44100, 1024
SILENT_AAC = bytes([0x21, 0x10, 0x04, 0x60, 0x8C, 0x1C])  # AAC-LC mono silent raw frame


def box(kind, *payload):
    data = b"".join(payload)
    return struct.pack(">I", 8 + len(data)) + kind + data


def full(kind, version, flags, *payload):
    return box(kind, struct.pack(">I", (version << 24) | flags), *payload)


def esds():
    asc = bytes([0x12, 0x08])  # AAC LC, 44.1 kHz, mono
    dsi = b"\x05" + bytes([len(asc)]) + asc
    dcd_body = bytes([0x40, 0x15]) + b"\x00\x00\x00" + struct.pack(">II", 128000, 128000) + dsi
    dcd = b"\x04" + bytes([len(dcd_body)]) + dcd_body
    es_body = struct.pack(">H", 1) + b"\x00" + dcd + b"\x06\x01\x02"
    return full(b"esds", 0, 0, b"\x03" + bytes([len(es_body)]) + es_body)


def ilst_item(kind, data, data_type):
    return box(kind, box(b"data", struct.pack(">II", data_type, 0), data))


def m4a(case, lyrics=None):
    _, title, artist, album, cover = case
    n = SECONDS * RATE // FRAME
    duration = n * FRAME
    items = [ilst_item(b"\xa9nam", title.encode("utf-8"), 1),
             ilst_item(b"\xa9ART", artist.encode("utf-8"), 1),
             ilst_item(b"\xa9alb", album.encode("utf-8"), 1)]
    if cover:
        items.append(ilst_item(b"covr", COVER, 14))  # 14 = PNG
    if lyrics:
        items.append(ilst_item(b"\xa9lyr", lyrics.encode("utf-8"), 1))
    hdlr_meta = full(b"hdlr", 0, 0, struct.pack(">I", 0) + b"mdir" + b"appl" + b"\x00" * 9)
    udta = box(b"udta", full(b"meta", 0, 0, hdlr_meta, box(b"ilst", *items)))

    def build(offset):
        mp4a = box(b"mp4a", b"\x00" * 6 + struct.pack(">H", 1) + b"\x00" * 8 + struct.pack(">HHHH", 1, 16, 0, 0)
                   + struct.pack(">I", RATE << 16), esds())
        stbl = box(b"stbl", full(b"stsd", 0, 0, struct.pack(">I", 1), mp4a),
                   full(b"stts", 0, 0, struct.pack(">III", 1, n, FRAME)),
                   full(b"stsc", 0, 0, struct.pack(">IIII", 1, 1, n, 1)),
                   full(b"stsz", 0, 0, struct.pack(">II", len(SILENT_AAC), n)),
                   full(b"stco", 0, 0, struct.pack(">II", 1, offset)))
        tkhd = full(b"tkhd", 0, 3, struct.pack(">IIIII", 0, 0, 1, 0, duration * 1000 // RATE) + b"\x00" * 8
                    + struct.pack(">hhhH", 0, 0, 0x0100, 0)
                    + struct.pack(">9I", 0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000) + struct.pack(">II", 0, 0))
        mdhd = full(b"mdhd", 0, 0, struct.pack(">IIII", 0, 0, RATE, duration) + struct.pack(">HH", 0x55C4, 0))
        hdlr = full(b"hdlr", 0, 0, struct.pack(">I", 0) + b"soun" + b"\x00" * 12 + b"SoundHandler\x00")
        minf = box(b"minf", full(b"smhd", 0, 0, struct.pack(">HH", 0, 0)),
                   box(b"dinf", full(b"dref", 0, 0, struct.pack(">I", 1), full(b"url ", 0, 1))), stbl)
        trak = box(b"trak", tkhd, box(b"mdia", mdhd, hdlr, minf))
        mvhd = full(b"mvhd", 0, 0, struct.pack(">IIII", 0, 0, 1000, duration * 1000 // RATE) + struct.pack(">IH", 0x10000, 0x100)
                    + b"\x00" * 10 + struct.pack(">9I", 0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)
                    + b"\x00" * 24 + struct.pack(">I", 2))
        return box(b"moov", mvhd, trak, udta)

    ftyp = box(b"ftyp", b"M4A ", struct.pack(">I", 0), b"M4A mp42isom")
    moov = build(0)
    moov = build(len(ftyp) + len(moov) + 8)
    return ftyp + moov + box(b"mdat", SILENT_AAC * n)


# --- FLAC --------------------------------------------------------------------------------------

def flac(case):
    _, title, artist, album, cover = case
    # 4096-sample blocks at 44.1 kHz: ~0.093 s each.
    return flac_file(round(SECONDS / 0.0929), tags=[("TITLE", title), ("ARTIST", artist), ("ALBUM", album)],
                     picture=COVER if cover else None)


def safe_name(text):
    """Unicode file/folder name: only characters SAF and FAT/exFAT reject are replaced."""
    return "".join("_" if c in '\\/:*?"<>|' else c for c in text)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    variants = [
        ("mp3", "id3v23_utf16", lambda c: mp3(3, 1, c)),
        ("mp3", "id3v24_utf8", lambda c: mp3(4, 3, c)),
        ("mp3", "id3v24_utf16be", lambda c: mp3(4, 2, c)),
        ("flac", "vorbis", flac),
        ("m4a", "ilst", m4a),
    ]
    rows = []
    for case in CASES:
        key, title, artist, album, cover = case
        for ext, tag, build in variants:
            asset = f"{key}_{tag}.{ext}"
            with open(os.path.join(OUT_DIR, asset), "wb") as f:
                f.write(build(case))
            path = f"uni/{safe_name(artist)}/{safe_name(album)}/{safe_name(title)} ({tag}).{ext}"
            rows.append((asset, path, title, artist, album, "exact", "cover" if cover else "-"))
        if any("぀" <= ch <= "ヿ" or "一" <= ch <= "鿿" for ch in title + artist + album):
            try:
                data = mp3(3, -1, case)
            except UnicodeEncodeError:
                continue
            asset = f"{key}_id3v23_sjis_as_latin1.mp3"
            with open(os.path.join(OUT_DIR, asset), "wb") as f:
                f.write(data)
            path = f"uni/{safe_name(artist)}/{safe_name(album)}/{safe_name(title)} (sjis).mp3"
            rows.append((asset, path, title, artist, album, "undecodable", "cover" if cover else "-"))
        if all(ord(ch) <= 0xFF for ch in title + artist + album):
            asset = f"{key}_id3v23_latin1.mp3"
            with open(os.path.join(OUT_DIR, asset), "wb") as f:
                f.write(mp3(3, 0, case))
            path = f"uni/{safe_name(artist)}/{safe_name(album)}/{safe_name(title)} (latin1).mp3"
            rows.append((asset, path, title, artist, album, "exact", "cover" if cover else "-"))
    (key, title, artist, album, _), files = stress_files()
    for asset, data in files.items():
        with open(os.path.join(OUT_DIR, asset), "wb") as f:
            f.write(data)
        tag = asset[len(key) + 1:asset.rindex(".")]
        path = f"uni/{safe_name(artist)}/{safe_name(album)}/{safe_name(title)} ({tag}){asset[asset.rindex('.'):]}"
        rows.append((asset, path, title, artist, album, "exact", "cover" if "cover" in asset or "unsync" in asset else "-"))
    with open(os.path.join(OUT_DIR, "manifest.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write("\t".join(row) + "\n")
    print(f"{len(rows)} files in {os.path.normpath(OUT_DIR)}")


if __name__ == "__main__":
    main()
