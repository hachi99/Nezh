/*
 * Nezh - NOT part of the Java Apple Lossless Decoder (Peter McQuillan, see
 * third_party/java-alac-decoder). Written for Nezh to drive that decoder's core one packet at a
 * time with the data Media3's MP4 extractor provides. It lives in the decoder's package only
 * because the core classes are package-private.
 */
package com.beatofthedrum.alacdecoder;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Decodes ALAC packets (one MP4 sample each) to interleaved little-endian PCM, 16 or 24 bit,
 * mono or stereo. Every packet decodes on its own: the core keeps no state between packets, so
 * seeking needs nothing here.
 *
 * <p>What the core does not check is checked here: the magic cookie, each frame header (element,
 * sample count, uncompressed bytes), and, after decoding, that the frame ended inside the packet
 * with an END element. Any failure, including any exception from the core, is an
 * {@link AlacDecodeException}; nothing is returned for that packet.
 *
 * <p>Not thread-safe: use one instance per decoding thread.
 */
public final class AlacPacketDecoder {

    /** Per-channel buffer size of {@link AlacFile}: no frame may hold more samples. */
    public static final int MAX_FRAME_LENGTH = 16384;
    /** The core reads up to two bytes past its position; packets are copied with this much room. */
    static final int INPUT_PADDING = 8;
    /** Size of ALACSpecificConfig, the magic cookie. */
    static final int COOKIE_SIZE = 24;
    private static final int ELEMENT_SCE = 0;
    private static final int ELEMENT_CPE = 1;
    private static final int ELEMENT_END = 7;

    /** The validated contents of a magic cookie. */
    public static final class Config {
        public final int frameLength;
        public final int bitDepth;
        public final int channels;
        public final int sampleRate;
        /** Largest packet in bytes, or 0 if the encoder did not state it. */
        public final int maxFrameBytes;

        Config(int frameLength, int bitDepth, int channels, int sampleRate, int maxFrameBytes) {
            this.frameLength = frameLength;
            this.bitDepth = bitDepth;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.maxFrameBytes = maxFrameBytes;
        }

        /** Bytes of PCM in one full frame. */
        public int maxOutputBytes() {
            return frameLength * channels * (bitDepth / 8);
        }
    }

    /**
     * Reads and validates a magic cookie (the body of the 'alac' box after its full-box header, as
     * Media3 stores it in Format.initializationData).
     */
    public static Config parseCookie(byte[] cookie) throws AlacDecodeException {
        require(cookie != null && cookie.length >= COOKIE_SIZE, "magic cookie shorter than " + COOKIE_SIZE + " bytes");
        int frameLength = readInt(cookie, 0);
        int compatibleVersion = cookie[4] & 0xff;
        int bitDepth = cookie[5] & 0xff;
        int kb = cookie[8] & 0xff;
        int channels = cookie[9] & 0xff;
        int maxFrameBytes = readInt(cookie, 12);
        int sampleRate = readInt(cookie, 20);
        require(compatibleVersion == 0, "unsupported compatible version " + compatibleVersion);
        require(frameLength > 0 && frameLength <= MAX_FRAME_LENGTH, "frame length " + frameLength + " (1-" + MAX_FRAME_LENGTH + ")");
        require(bitDepth == 16 || bitDepth == 24, "unsupported bit depth " + bitDepth + " (16 and 24 are supported)");
        require(channels == 1 || channels == 2, "unsupported channel count " + channels + " (mono and stereo are supported)");
        // Rice parameter kb: 14 is the only value encoders are known to use; above 24 the core's
        // bit reader would be asked for more bits than it supports in one read.
        require(kb > 0 && kb <= 24, "Rice parameter kb " + kb + " out of range (1-24)");
        require(sampleRate > 0, "sample rate " + sampleRate);
        return new Config(frameLength, bitDepth, channels, sampleRate, Math.max(0, maxFrameBytes));
    }

    private final Config config;
    private final AlacFile alac;
    /** The core's output: one int per sample at 16 bit, one int per byte at 24 bit. */
    private final int[] decoded;
    private byte[] input = new byte[0];

    public AlacPacketDecoder(byte[] cookie) throws AlacDecodeException {
        config = parseCookie(cookie);
        alac = AlacDecodeUtils.create_alac(config.bitDepth, config.channels);
        // alac_set_info expects the cookie after 24 bytes that its original caller (the file
        // parser) filled with box headers it skips; the values are bytes, 0-255.
        int[] codecData = new int[COOKIE_SIZE * 2];
        for (int i = 0; i < COOKIE_SIZE; i++) codecData[COOKIE_SIZE + i] = cookie[i] & 0xff;
        AlacDecodeUtils.alac_set_info(alac, codecData);
        // Mono writes one value (16 bit) or three bytes (24 bit) past the last sample: room for both.
        decoded = new int[config.frameLength * config.channels * 3 + 8];
    }

    public Config config() {
        return config;
    }

    /**
     * Decodes the packet between the buffer's position and limit (the position does not move),
     * whatever kind of buffer it is.
     *
     * @return the number of PCM frames (samples per channel) written to {@code pcmOut}, whose
     *     length must be at least {@link Config#maxOutputBytes()}
     */
    public int decode(ByteBuffer packet, byte[] pcmOut) throws AlacDecodeException {
        int length = packet.remaining();
        ensureInputCapacity(length);
        packet.duplicate().get(input, 0, length);
        return decodeInput(length, pcmOut);
    }

    /** As {@link #decode(ByteBuffer, byte[])}, for {@code length} bytes of {@code packet} from {@code offset}. */
    public int decode(byte[] packet, int offset, int length, byte[] pcmOut) throws AlacDecodeException {
        ensureInputCapacity(length);
        System.arraycopy(packet, offset, input, 0, length);
        return decodeInput(length, pcmOut);
    }

    private void ensureInputCapacity(int length) {
        if (input.length < length + INPUT_PADDING) input = new byte[length + INPUT_PADDING];
    }

    private int decodeInput(int length, byte[] pcmOut) throws AlacDecodeException {
        Arrays.fill(input, length, length + INPUT_PADDING, (byte) 0);
        int samples = checkHeader(length);
        try {
            AlacDecodeUtils.decode_frame(alac, input, decoded, 0);
        } catch (RuntimeException e) {
            throw new AlacDecodeException("decoding failed: " + e.getMessage(), e);
        }
        checkTrailer(length);
        int values = samples * config.channels;
        if (config.bitDepth == 16) {
            for (int i = 0; i < values; i++) {
                pcmOut[2 * i] = (byte) decoded[i];
                pcmOut[2 * i + 1] = (byte) (decoded[i] >> 8);
            }
        } else {
            for (int i = 0; i < values * 3; i++) pcmOut[i] = (byte) decoded[i];
        }
        return samples;
    }

    /** Validates the frame header the core trusts and returns the frame's sample count. */
    private int checkHeader(int length) throws AlacDecodeException {
        BitReader bits = new BitReader(input, length);
        int element = bits.read(3);
        int expected = config.channels == 1 ? ELEMENT_SCE : ELEMENT_CPE;
        require(element == expected, "element " + element + ", expected " + expected + " for " + config.channels + " channel(s)");
        bits.read(4); // element instance tag
        require(bits.read(12) == 0, "non-zero reserved header bits");
        boolean hasSize = bits.read(1) != 0;
        int shiftBytes = bits.read(2);
        bits.read(1); // escape (uncompressed) flag
        // The core only merges uncompressed low bytes into 24-bit output: at 16 bit they would be
        // dropped and the samples would be wrong.
        require(config.bitDepth == 24 ? shiftBytes <= 2 : shiftBytes == 0,
                shiftBytes + " uncompressed bytes per sample at " + config.bitDepth + " bit");
        int samples = hasSize ? bits.read(32) : config.frameLength;
        require(samples > 0 && samples <= config.frameLength, "frame of " + samples + " samples, maximum " + config.frameLength);
        return samples;
    }

    /** The core does not check its reads against the packet: it must have stopped inside it, at END. */
    private void checkTrailer(int length) throws AlacDecodeException {
        long consumedBits = alac.ibIdx * 8L + alac.input_buffer_bitaccumulator;
        require(consumedBits + 3 <= length * 8L, "frame data runs past the end of the packet");
        BitReader bits = new BitReader(input, length);
        bits.skip(consumedBits);
        int tag = bits.read(3);
        require(tag == ELEMENT_END, "element " + tag + " after the audio data, expected END");
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    private static void require(boolean condition, String message) throws AlacDecodeException {
        if (!condition) throw new AlacDecodeException(message);
    }

    /** Bounds-checked big-endian bit reader over the first {@code length} bytes. */
    private static final class BitReader {
        private final byte[] data;
        private final long limitBits;
        private long position;

        BitReader(byte[] data, int length) {
            this.data = data;
            this.limitBits = length * 8L;
        }

        void skip(long bits) {
            position += bits;
        }

        int read(int count) throws AlacDecodeException {
            require(position + count <= limitBits, "frame header runs past the end of the packet");
            int value = 0;
            for (int i = 0; i < count; i++, position++) {
                value = (value << 1) | ((data[(int) (position >>> 3)] >> (7 - (int) (position & 7))) & 1);
            }
            return value;
        }
    }

    /** A packet or magic cookie that cannot be decoded. */
    public static final class AlacDecodeException extends Exception {
        private static final long serialVersionUID = 1L;

        AlacDecodeException(String message) {
            super(message);
        }

        AlacDecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
