"""Generates the audio fixtures used by FormatCompatibilityTest (androidTest assets).

Pure Python standard library, deterministic output, no external encoders.

Formats that need a real encoder (AAC/M4A, ADTS AAC, Opus) are generated on the device at test
time with Android's MediaCodec encoders. ALAC and Ogg Vorbis have no encoder available here or on
Android, so they are not generated (see tools/fixtures/README.md).

MP3 files are built frame by frame from valid MPEG-1 Layer III frames whose side information
declares no audio data, which decoders output as digital silence. This gives exact, known
durations and full control over bitrate changes and the Xing header, which is what the duration
and seek checks need.

Usage:  python tools/fixtures/make_audio_fixtures.py
"""
import math
import os
import random
import struct
import zlib

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "androidTest", "assets", "formats")

# --- MP3 (MPEG-1 Layer III, 44.1 kHz, mono) -------------------------------------------------

MP3_SAMPLE_RATE = 44100
MP3_SAMPLES_PER_FRAME = 1152
MP3_BITRATE_INDEX = {32: 1, 40: 2, 48: 3, 56: 4, 64: 5, 80: 6, 96: 7, 112: 8, 128: 9,
                     160: 10, 192: 11, 224: 12, 256: 13, 320: 14}
MP3_MONO_SIDE_INFO = 17


def mp3_frame(kbps, payload=b""):
    """One silent frame (no padding). Optional payload goes after the side information."""
    size = 144 * kbps * 1000 // MP3_SAMPLE_RATE
    header = bytes([0xFF, 0xFB, (MP3_BITRATE_INDEX[kbps] << 4) | 0x00, 0xC4])  # 44.1k, mono
    body = bytes(MP3_MONO_SIDE_INFO) + payload
    assert len(header) + len(body) <= size
    return header + body + bytes(size - len(header) - len(body))


def frames_for_seconds(seconds):
    return round(seconds * MP3_SAMPLE_RATE / MP3_SAMPLES_PER_FRAME)


def xing_frame(frames, total_bytes, frame_offsets):
    """Xing header (frames + bytes + TOC) inside a silent frame, as LAME writes it."""
    toc = bytearray(100)
    for i in range(100):
        frame_index = min(len(frame_offsets) - 1, int(i / 100 * len(frame_offsets)))
        toc[i] = min(255, int(frame_offsets[frame_index] * 256 / total_bytes))
    payload = b"Xing" + struct.pack(">I", 0x7) + struct.pack(">I", frames) + struct.pack(">I", total_bytes) + bytes(toc)
    return mp3_frame(128, payload)


def id3v23(frames):
    """ID3v2.3 tag from (frame_id, body_bytes) pairs. Uses synchsafe size, no unsynchronisation."""
    body = b"".join(fid.encode() + struct.pack(">I", len(data)) + b"\x00\x00" + data for fid, data in frames)
    size = len(body)
    synchsafe = bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F])
    return b"ID3\x03\x00\x00" + synchsafe + body


def text_frame(fid, text):
    return fid, b"\x01" + "﻿".encode("utf-16-le") + text.encode("utf-16-le")  # UTF-16 with BOM


def apic_frame(png):
    return "APIC", b"\x00" + b"image/png\x00" + b"\x03" + b"\x00" + png  # front cover, empty description


# --- PNG (solid colour; tiny file, large decoded bitmap) --------------------------------------

def png(width, height, rgb):
    row = b"\x00" + bytes(rgb) * width
    raw = row * height
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


# --- FLAC (constant subframes = silence, 44.1 kHz, mono, 16 bit) -------------------------------

FLAC_BLOCK = 4096


def crc8(data):
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def crc16(data):
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x8005) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def utf8_number(n):
    if n < 0x80:
        return bytes([n])
    if n < 0x800:
        return bytes([0xC0 | (n >> 6), 0x80 | (n & 0x3F)])
    return bytes([0xE0 | (n >> 12), 0x80 | ((n >> 6) & 0x3F), 0x80 | (n & 0x3F)])


def flac_frame(number):
    header = bytes([0xFF, 0xF8, (0b1100 << 4) | 0b1001, (0b0000 << 4) | (0b100 << 1)]) + utf8_number(number)
    header += bytes([crc8(header)])
    subframe = bytes([0x00]) + struct.pack(">h", 0)  # CONSTANT subframe, value 0
    frame = header + subframe
    return frame + struct.pack(">H", crc16(frame))


def flac_metadata_block(kind, data, last):
    return bytes([(0x80 if last else 0) | kind]) + struct.pack(">I", len(data))[1:] + data


def flac_file(blocks, tags=None, picture=None):
    total = blocks * FLAC_BLOCK
    frames = [flac_frame(i) for i in range(blocks)]
    sizes = [len(f) for f in frames]
    info = struct.pack(">HH", FLAC_BLOCK, FLAC_BLOCK)
    info += struct.pack(">I", min(sizes))[1:] + struct.pack(">I", max(sizes))[1:]
    packed = (44100 << 44) | (0 << 41) | (15 << 36) | total  # rate, channels-1, bps-1, samples
    info += packed.to_bytes(8, "big") + bytes(16)  # no MD5
    blocks_meta = [(0, info)]
    if tags:
        vendor = b"nezh-fixtures"
        comments = b"".join(struct.pack("<I", len(c)) + c for c in (f"{k}={v}".encode() for k, v in tags))
        blocks_meta.append((4, struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", len(tags)) + comments))
    if picture:
        mime = b"image/png"
        pic = struct.pack(">I", 3) + struct.pack(">I", len(mime)) + mime + struct.pack(">I", 0)
        pic += struct.pack(">IIII", 600, 600, 24, 0) + struct.pack(">I", len(picture)) + picture
        blocks_meta.append((6, pic))
    meta = b"".join(flac_metadata_block(k, d, i == len(blocks_meta) - 1) for i, (k, d) in enumerate(blocks_meta))
    return b"fLaC" + meta + b"".join(frames)


# --- WAV --------------------------------------------------------------------------------------

def wav_tone(seconds, rate=22050, freq=440):
    n = int(seconds * rate)
    samples = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * freq * i / rate))) for i in range(n))
    fmt = struct.pack("<HHIIHH", 1, 1, rate, rate * 2, 2, 16)
    return b"RIFF" + struct.pack("<I", 36 + len(samples)) + b"WAVE" + b"fmt " + struct.pack("<I", 16) + fmt + \
        b"data" + struct.pack("<I", len(samples)) + samples


# --- MIDI (valid file, format Media3 has no extractor for by default) ---------------------------

def midi():
    track = bytes([0x00, 0x90, 0x3C, 0x40, 0x60, 0x80, 0x3C, 0x40, 0x00, 0xFF, 0x2F, 0x00])
    return b"MThd" + struct.pack(">IHHH", 6, 0, 1, 96) + b"MTrk" + struct.pack(">I", len(track)) + track


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    cover = png(600, 600, (200, 60, 60))
    huge_cover = png(3000, 3000, (60, 60, 200))
    tags = [text_frame("TIT2", "Título de prueba"), text_frame("TPE1", "Artista de prueba"),
            text_frame("TALB", "Álbum de prueba")]

    cbr = b"".join(mp3_frame(128) for _ in range(frames_for_seconds(20)))
    half = frames_for_seconds(10)
    vbr_frames = [mp3_frame(32) for _ in range(half)] + [mp3_frame(320) for _ in range(half)]
    vbr = b"".join(vbr_frames)
    offsets, pos = [], 0
    for f in vbr_frames:
        offsets.append(pos)
        pos += len(f)
    xing = xing_frame(len(vbr_frames), len(vbr), offsets)

    files = {
        "mp3_cbr_tagged.mp3": id3v23(tags + [apic_frame(cover)]) + cbr,
        "mp3_cbr_untagged.mp3": cbr,
        "mp3_partial_tags.mp3": id3v23([text_frame("TPE1", "Solo artista")]) + cbr,
        "mp3_vbr_xing.mp3": xing + vbr,
        "mp3_vbr_noheader.mp3": vbr,
        "mp3_large_artwork.mp3": id3v23(tags + [apic_frame(huge_cover)]) + b"".join(mp3_frame(128) for _ in range(frames_for_seconds(5))),
        "flac_tagged.flac": flac_file(215, tags=[("TITLE", "Título FLAC"), ("ARTIST", "Artista FLAC"), ("ALBUM", "Álbum FLAC")], picture=cover),
        "flac_untagged.flac": flac_file(215),
        "wav_pcm16.wav": wav_tone(5),
        "unsupported.mid": midi(),
        "corrupt.mp3": random.Random(42).randbytes(64 * 1024),
    }
    for name, data in files.items():
        with open(os.path.join(OUT_DIR, name), "wb") as f:
            f.write(data)
        print(f"{name:28s} {len(data):>9,d} bytes")


if __name__ == "__main__":
    main()
