#!/usr/bin/env bash
# Generates the test clips used by the instrumented merge tests (and the ffprobe checks).
# Every clip is 3 s long, has a full-frame white flash at t=1.0 s and (if it has audio) a
# 1 kHz beep starting at exactly t=1.0 s, so A/V sync can be measured in the merged output.
#
#   p1/p2/p3.mp4   phone-style portrait: H.264 1280x720 stored, rotation flag 90°, AAC 48k stereo
#   p_noaudio.mp4  same as p1 without an audio track
#   l_hevc.mp4     landscape H.265 1280x720, no rotation, AAC 48k stereo
set -euo pipefail
out="${1:?usage: make_test_clips.sh <output dir>}"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

make() { # name bg-colour video-codec-args... ; set AUDIO=0 for no audio
  local name="$1" bg="$2"; shift 2
  local audio_in=() audio_map=() audio_codec=()
  if [ "${AUDIO:-1}" = 1 ]; then
    audio_in=(-f lavfi -i "sine=f=1000:r=48000:d=3")
    audio_map=(-map "[a]")
    audio_codec=(-c:a aac -b:a 128k -ar 48000 -ac 2)
  fi
  local fc="[0][1]overlay=x='(W-w)*t/3':y=(H-h)/2,drawbox=x=0:y=0:w=iw:h=ih:c=white:t=fill:enable='between(t,1,1.05)'[v]"
  if [ "${AUDIO:-1}" = 1 ]; then
    fc="$fc;[2]volume=volume=0:enable='lt(t,1)+gte(t,1.1)',aformat=channel_layouts=stereo[a]"
  fi
  ffmpeg -v error -y \
    -f lavfi -i "color=c=$bg:s=1280x720:r=30:d=3" \
    -f lavfi -i "testsrc2=s=320x180:r=30:d=3" \
    "${audio_in[@]}" \
    -filter_complex "$fc" -map "[v]" "${audio_map[@]}" \
    "$@" -pix_fmt yuv420p -g 30 "${audio_codec[@]}" -movflags +faststart "$tmp/$name.mp4"
}

H264=(-c:v libx264 -profile:v high -preset veryfast -b:v 3M -maxrate 3M -bufsize 6M)
make p1 0x605040 "${H264[@]}"
make p2 0x406050 "${H264[@]}"
make p3 0x405060 "${H264[@]}"
AUDIO=0 make p_noaudio 0x606040 "${H264[@]}"
make l_hevc 0x604060 -c:v libx265 -preset veryfast -b:v 2M -tag:v hvc1 -x265-params log-level=error

# Phones store portrait video as landscape frames plus a 90° display rotation
# (ffprobe shows "rotation of -90"); reproduce that for the p* clips.
for n in p1 p2 p3 p_noaudio; do
  ffmpeg -v error -y -display_rotation:v:0 -90 -i "$tmp/$n.mp4" -c copy -movflags +faststart "$out/$n.mp4"
done
cp "$tmp/l_hevc.mp4" "$out/l_hevc.mp4"
ls -l "$out"
