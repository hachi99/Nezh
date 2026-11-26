"""
ALAC fixtures derived from existing ones, for the Java ALAC decoder's error paths (pure Python,
deterministic; run after the fixtures they derive from exist):

- formats/alac_bad_prediction.m4a  alac_16_44k_stereo.m4a with packet 5's first prediction type set
                                   to 1, which no ALAC decoder implementation here handles: the file
                                   must fail as a decoding error, never play wrong audio.
- codecs/tone_alac_20bit.m4a       tone_alac.m4a whose magic cookie (and sample entry) says 20-bit
                                   samples: an ALAC configuration Nezh's decoder does not support,
                                   so on a device without its own ALAC decoder the file has no
                                   playable audio track.

Usage: python tools/fixtures/make_alac_variant_fixtures.py
"""

import os
import struct

ASSETS = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "androidTest", "assets")


def boxes(data, start, end):
    while start + 8 <= end:
        size, kind = struct.unpack(">I4s", data[start:start + 8])
        if size < 8 or start + size > end:
            raise ValueError(f"bad box at {start}")
        yield kind.decode("latin-1"), start, start + 8, start + size
        start += size


def find(data, start, end, path):
    for kind, box_start, body, box_end in boxes(data, start, end):
        if kind == path[0]:
            return (box_start, body, box_end) if len(path) == 1 else find(data, body, box_end, path[1:])
    raise ValueError(f"no {path[0]} box")


def stbl(data):
    return find(data, 0, len(data), ["moov", "trak", "mdia", "minf", "stbl"])


def alac_entry_and_cookie(data):
    _, body, end = stbl(data)
    _, stsd, _ = find(data, body, end, ["stsd"])
    entry_start = stsd + 8  # version/flags + entry count
    _, entry_body, entry_end = next(boxes(data, entry_start, end))[1:]
    _, cookie_body, _ = find(data, entry_body + 28, entry_end, ["alac"])
    return entry_body, cookie_body + 4  # the cookie follows the 'alac' full-box header


def sample_offset(data, index):
    """File offset of sample `index` (the fixtures have one chunk)."""
    _, body, end = stbl(data)
    _, stsz, _ = find(data, body, end, ["stsz"])
    _, stco, _ = find(data, body, end, ["stco"])
    fixed, count = struct.unpack(">II", data[stsz + 4:stsz + 12])
    chunks = struct.unpack(">I", data[stco + 4:stco + 8])[0]
    if fixed or chunks != 1:
        raise ValueError("expected variable sample sizes in a single chunk")
    sizes = struct.unpack(f">{count}I", data[stsz + 12:stsz + 12 + 4 * count])
    first = struct.unpack(">I", data[stco + 8:stco + 12])[0]
    return first + sum(sizes[:index])


def set_bits(data, byte_offset, first_bit, count, value):
    for i in range(count):
        bit = first_bit + i
        mask = 0x80 >> (bit % 8)
        at = byte_offset + bit // 8
        data[at] = data[at] | mask if (value >> (count - 1 - i)) & 1 else data[at] & ~mask


def bad_prediction():
    data = bytearray(open(os.path.join(ASSETS, "formats", "alac_16_44k_stereo.m4a"), "rb").read())
    # Stereo frame without a size field: 23 header bits, interlacing shift and weight (16 bits),
    # then the first channel's 4-bit prediction type.
    set_bits(data, sample_offset(data, 5), 39, 4, 1)
    return bytes(data)


def twenty_bit():
    data = bytearray(open(os.path.join(ASSETS, "codecs", "tone_alac.m4a"), "rb").read())
    entry_body, cookie = alac_entry_and_cookie(data)
    data[cookie + 5] = 20                                  # ALACSpecificConfig bitDepth
    struct.pack_into(">H", data, entry_body + 18, 20)      # AudioSampleEntry samplesize
    return bytes(data)


if __name__ == "__main__":
    for name, content in [("formats/alac_bad_prediction.m4a", bad_prediction()), ("codecs/tone_alac_20bit.m4a", twenty_bit())]:
        path = os.path.join(ASSETS, name)
        with open(path, "wb") as f:
            f.write(content)
        print(f"{name}: {len(content)} bytes")
