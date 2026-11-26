# Nezh's changes to the Java Apple Lossless Decoder

Only `AlacDecodeUtils.java` is modified. The exact change is `nezh-changes.diff` (against the file
at commit `afd5bf30`; `git apply -p1` on the original reproduces Nezh's file). Behaviour for valid
input is unchanged; each change replaces a silent wrong result or an uncontrolled array access with
an exception, which `AlacPacketDecoder` reports as a decoding error.

| Id | Where | Before | After |
|---|---|---|---|
| D2 | `decode_frame`, mono and stereo, prediction type other than 0 (3 places) | Printed an "unhandled prediction type" message to standard error and returned samples that are not the decoded audio | Throws `IllegalStateException` |
| D2 | `decode_frame`, sample size 20 or 32 (2 places) | Printed an "unimplemented sample size" message to standard error and returned no new samples | Throws `IllegalStateException` (Nezh also rejects these cookies before decoding) |
| D5 | `entropy_rice_decode`, run of zeros | Wrote `blockSize` zeros even past the end of the frame (out of bounds, or into samples of the frame's tail) | Throws `IllegalStateException` if the run would pass the end of the frame |
| - | File header | - | A second comment block after the original header says the file was modified and points here; the original header is unchanged |

Checks done outside the original code, in Nezh's `AlacPacketDecoder.java`:

| Id | Check |
|---|---|
| D1 | Each packet is copied to a buffer with 8 zero bytes after it (the core reads up to two bytes ahead), and after decoding the read position must be inside the packet and followed by the END element |
| D4 | Magic cookie: 24 bytes or more, compatible version 0, frame length 1-16384, bit depth 16 or 24, 1 or 2 channels, Rice parameter kb 1-24, sample rate above 0. Frame header: element type matches the channel count, reserved bits 0, sample count 1 to frame length, uncompressed bytes 0 at 16 bit and 0-2 at 24 bit |
| - | Any exception from the core becomes a decoding error |
