#!/system/bin/sh
# Creates a synthetic music library of empty .mp3 files ON AN EMULATOR, for measuring Nezh's
# library scan through the real storage provider (ExternalStorageProvider).
#
# DO NOT run on a personal device: the files are indexed by MediaStore and would appear in other
# music apps. On physical devices use LibraryScanBenchmarkTest instead (no files are created).
#
# Usage (from the host):
#   adb push tools/library-bench/generate_library.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/generate_library.sh 10000          # creates /sdcard/Music/NezhBench
#   adb shell sh /data/local/tmp/generate_library.sh clean          # removes it
#
# Layout matches BenchTreeProvider: artist (100 tracks) / album (10 tracks) / tracks + cover.jpg.

ROOT=/sdcard/Music/NezhBench

if [ "$1" = "clean" ]; then
    rm -rf "$ROOT"
    echo "removed $ROOT"
    exit 0
fi

TOTAL=${1:?usage: generate_library.sh <tracks>|clean}
rm -rf "$ROOT"
i=0
while [ "$i" -lt "$TOTAL" ]; do
    artist=$((i / 100))
    album=$(((i % 100) / 10))
    dir=$(printf "%s/Artista %04d/Album %02d" "$ROOT" "$artist" "$album")
    if [ $((i % 10)) -eq 0 ]; then
        mkdir -p "$dir"
        : > "$dir/cover.jpg"
    fi
    : > "$(printf "%s/%02d - Cancion %05d.mp3" "$dir" $((i % 10 + 1)) "$i")"
    i=$((i + 1))
done
echo "created $TOTAL tracks in $ROOT"
