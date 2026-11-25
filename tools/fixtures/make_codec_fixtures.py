"""
Audio fixtures for codec handling (Hito 5.1): a file the device may not be able to decode must be
reported and skipped, never "played" in silence.

Writes into app/src/androidTest/assets/codecs/:
- tone_alac.m4a   ALAC (Apple Lossless) in MP4, 16-bit stereo 44.1 kHz, 440 Hz tone, ~3 s.
                  Frames use ALAC's uncompressed ("escape") mode, which every ALAC decoder supports.
- tone_pcm.wav    PCM 16-bit stereo 44.1 kHz WAV, same tone (WAV regression).
- tone_mp3.mp3    MPEG-1 Layer III frames (as the Unicode fixtures), ~3 s.

Pure Python, no external tools. Reuses the MP4 helpers of the Unicode fixtures.
"""

import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(__file__))
from make_unicode_fixtures import box, full, mp3_frame, frames_for_seconds, SECONDS  # noqa: E402

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "androidTest", "assets", "codecs")

RATE = 44100
CHANNELS = 2
BITS = 16
ALAC_FRAME = 4096


def tone(samples):
    """Interleaved 16-bit stereo samples of a 440 Hz tone at -12 dBFS."""
    amp = int(32767 * 0.25)
    out = []
    for i in range(samples):
        v = int(amp * math.sin(2 * math.pi * 440 * i / RATE))
        out += [v, v]
    return out


class BitWriter:
    def __init__(self):
        self.bits = []

    def put(self, value, n):
        for i in range(n - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    def bytes(self):
        bits = self.bits + [0] * (-len(self.bits) % 8)
        return bytes(int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8))


def alac_frame(pcm):
    """One full ALAC frame (4096 samples) in uncompressed ("escape") mode, channel pair element."""
    w = BitWriter()
    w.put(1, 3)   # ID_CPE: channel pair
    w.put(0, 4)   # element instance tag
    w.put(0, 12)  # unused, must be 0
    w.put(0, 1)   # partial frame: no (full 4096-sample frame)
    w.put(0, 2)   # bytes shifted
    w.put(1, 1)   # escape: uncompressed samples follow
    for s in pcm:
        w.put(s & 0xFFFF, 16)  # interleaved L, R, two's complement
    w.put(7, 3)   # ID_END
    return w.bytes()


def alac_m4a():
    frames = round(SECONDS * RATE / ALAC_FRAME)
    pcm = tone(frames * ALAC_FRAME)
    payloads = [alac_frame(pcm[i * ALAC_FRAME * 2:(i + 1) * ALAC_FRAME * 2]) for i in range(frames)]
    duration = frames * ALAC_FRAME
    frame_bytes = len(payloads[0])
    # ALACSpecificConfig (Apple's "magic cookie"), inside an 'alac' full box.
    config = struct.pack(">IBBBBBBHIII", ALAC_FRAME, 0, BITS, 40, 10, 14, CHANNELS, 255, frame_bytes,
                         frame_bytes * 8 * RATE // ALAC_FRAME, RATE)
    data = b"".join(payloads)

    def build(offset):
        entry = box(b"alac", b"\x00" * 6 + struct.pack(">H", 1) + b"\x00" * 8 + struct.pack(">HHHH", CHANNELS, BITS, 0, 0)
                    + struct.pack(">I", RATE << 16), full(b"alac", 0, 0, config))
        stbl = box(b"stbl", full(b"stsd", 0, 0, struct.pack(">I", 1), entry),
                   full(b"stts", 0, 0, struct.pack(">III", 1, frames, ALAC_FRAME)),
                   full(b"stsc", 0, 0, struct.pack(">IIII", 1, 1, frames, 1)),
                   full(b"stsz", 0, 0, struct.pack(">II", frame_bytes, frames)),
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
        return box(b"moov", mvhd, trak)

    ftyp = box(b"ftyp", b"M4A ", struct.pack(">I", 0), b"M4A mp42isom")
    moov = build(0)
    moov = build(len(ftyp) + len(moov) + 8)
    return ftyp + moov + box(b"mdat", data)


def wav():
    pcm = tone(SECONDS * RATE)
    data = struct.pack("<%dh" % len(pcm), *pcm)
    fmt = struct.pack("<HHIIHH", 1, CHANNELS, RATE, RATE * CHANNELS * BITS // 8, CHANNELS * BITS // 8, BITS)
    return (b"RIFF" + struct.pack("<I", 4 + 8 + len(fmt) + 8 + len(data)) + b"WAVE"
            + b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(data)) + data)


def mp3():
    return b"".join(mp3_frame(32) for _ in range(frames_for_seconds(SECONDS)))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    files = {"tone_alac.m4a": alac_m4a(), "tone_pcm.wav": wav(), "tone_mp3.mp3": mp3()}
    for name, data in files.items():
        with open(os.path.join(OUT_DIR, name), "wb") as f:
            f.write(data)
    print(f"{len(files)} files in {os.path.normpath(OUT_DIR)}")


if __name__ == "__main__":
    main()
