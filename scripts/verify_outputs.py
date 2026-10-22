#!/usr/bin/env python3
"""Checks merged videos with ffprobe/ffmpeg.

usage: verify_outputs.py <clips dir> <outputs dir>

For each expected output it checks: stream layout and display size, total duration, one
continuous timeline (no gaps or overlaps in video or audio packets), clean decoding,
audio/video sync (every clip's flash and beep happen together, at the right place on the
timeline), black bars where a clip was converted and none where it was copied, and that a
stream-copied result is about the size of its inputs. Exits non-zero on any failure.
"""
import json
import os
import re
import statistics
import subprocess
import sys

CASES = {
    # name: clips, displayed (w, h), which clip segments must have bars ("tb" top/bottom, "lr" left/right),
    #       whether the size must be ~ the sum of the inputs (stream copy only)
    "test_a.mp4": dict(clips=["p1", "p2", "p3"], display=(720, 1280), bars={}, copy_only=True),
    "test_b.mp4": dict(clips=["p1", "p2", "l_hevc"], display=(720, 1280), bars={2: "tb"}, copy_only=False),
    "test_c_portrait.mp4": dict(clips=["p1", "l_hevc"], display=(720, 1280), bars={1: "tb"}, copy_only=False),
    "test_c_landscape.mp4": dict(clips=["p1", "l_hevc"], display=(1280, 720), bars={0: "lr"}, copy_only=False),
    "test_d.mp4": dict(clips=["p1", "p_noaudio", "p2"], display=(720, 1280), bars={}, copy_only=True),
}

SYNC_TOLERANCE = 0.050  # seconds; about one video frame plus AAC framing


def run(*args):
    return subprocess.run(args, capture_output=True, text=True)


def probe(path):
    r = run("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path)
    return json.loads(r.stdout)


def packet_times(path, stream):
    r = run("ffprobe", "-v", "error", "-select_streams", stream, "-show_entries", "packet=pts_time,duration_time", "-of", "csv=p=0", path)
    out = []
    for line in r.stdout.split():
        parts = line.split(",")
        if parts[0] not in ("", "N/A"):
            out.append((float(parts[0]), float(parts[1]) if len(parts) > 1 and parts[1] not in ("", "N/A") else 0.0))
    return sorted(out)


def rotation(stream):
    for sd in stream.get("side_data_list", []):
        if "rotation" in sd:
            return int(sd["rotation"])
    return int(stream.get("tags", {}).get("rotate", 0))


def display_size(stream):
    w, h = stream["width"], stream["height"]
    return (h, w) if rotation(stream) % 180 else (w, h)


def flash_times(path):
    """Times of frames much brighter than the clip around them (the white flash)."""
    r = run("ffmpeg", "-v", "error", "-i", path, "-vf", "signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-", "-f", "null", "-")
    times, values = [], []
    t = None
    for line in r.stdout.splitlines():
        m = re.search(r"pts_time:([0-9.]+)", line)
        if m:
            t = float(m.group(1))
        m = re.search(r"YAVG=([0-9.]+)", line)
        if m and t is not None:
            times.append(t)
            values.append(float(m.group(1)))
    if not values:
        return []
    # Compare each frame with the frames around it, so a flash inside a letterboxed
    # (mostly black) clip still stands out.
    hits = []
    for i, (t, v) in enumerate(zip(times, values)):
        around = [values[j] for j in range(len(values)) if abs(times[j] - t) <= 0.5]
        base = statistics.median(around)
        if v > base * 1.6 and v - base > 20:
            hits.append(t)
    # Group consecutive flash frames into one event (its first frame).
    events = []
    for t in hits:
        if not events or t - events[-1][-1] > 0.2:
            events.append([t])
        else:
            events[-1].append(t)
    return [e[0] for e in events]


def beep_times(path, audio_end):
    r = run("ffmpeg", "-v", "info", "-i", path, "-af", "silencedetect=n=-40dB:d=0.3", "-vn", "-f", "null", "-")
    ends = [float(x) for x in re.findall(r"silence_end: ([0-9.]+)", r.stderr)]
    # silencedetect also reports the end of the stream as a silence end; that's not a beep.
    return [e for e in ends if e < audio_end - 0.1]


def gray_frame(path, t, w, h):
    r = subprocess.run(["ffmpeg", "-v", "error", "-ss", str(t), "-i", path, "-frames:v", "1", "-vf", "format=gray", "-f", "rawvideo", "-"],
                       capture_output=True)
    data = r.stdout
    if len(data) != w * h:
        raise RuntimeError(f"frame at {t}s is {len(data)} bytes, expected {w}x{h}")
    return data


def region_mean(data, w, x0, y0, x1, y1):
    total, n = 0, 0
    for y in range(y0, y1, 4):
        row = data[y * w + x0: y * w + x1: 4]
        total += sum(row)
        n += len(row)
    return total / max(n, 1)


def bars_of(data, w, h):
    """Mean luma of the outer 8% edge strips (middle part of each edge) and of the centre."""
    sh, sw = int(h * 0.08), int(w * 0.08)
    x0, x1, y0, y1 = int(w * 0.4), int(w * 0.6), int(h * 0.4), int(h * 0.6)
    return dict(
        top=region_mean(data, w, x0, 0, x1, sh),
        bottom=region_mean(data, w, x0, h - sh, x1, h),
        left=region_mean(data, w, 0, y0, sw, y1),
        right=region_mean(data, w, w - sw, y0, w, y1),
        centre=region_mean(data, w, x0, y0, x1, y1),
    )


def check_case(name, case, clips_dir, out_path):
    failures = []
    notes = []

    def check(cond, msg):
        (notes if cond else failures).append(("ok   " if cond else "FAIL ") + msg)

    inputs = [os.path.join(clips_dir, c + ".mp4") for c in case["clips"]]
    in_durations = [float(probe(p)["format"]["duration"]) for p in inputs]
    in_has_audio = [any(s["codec_type"] == "audio" for s in probe(p)["streams"]) for p in inputs]
    starts = [sum(in_durations[:i]) for i in range(len(inputs))]
    expected = sum(in_durations)

    info = probe(out_path)
    streams = info["streams"]
    v = [s for s in streams if s["codec_type"] == "video"]
    a = [s for s in streams if s["codec_type"] == "audio"]
    check(len(v) == 1 and len(a) == 1, f"one video + one audio stream (got {len(v)} + {len(a)})")
    if not v:
        return failures, notes
    v = v[0]
    dw, dh = display_size(v)
    check((dw, dh) == case["display"], f"displayed size {dw}x{dh} (stored {v['width']}x{v['height']}, rotation {rotation(v)}), expected {case['display'][0]}x{case['display'][1]}")

    dur = float(info["format"]["duration"])
    check(abs(dur - expected) <= 0.15, f"duration {dur:.3f}s, sum of clips {expected:.3f}s")

    vp = packet_times(out_path, "v:0")
    vd = statistics.median([b[0] - a_[0] for a_, b in zip(vp, vp[1:])])
    vgaps = [b[0] - a_[0] for a_, b in zip(vp, vp[1:]) if b[0] - a_[0] > vd * 1.6]
    check(vp[0][0] <= 0.05, f"video starts at {vp[0][0]:.3f}s")
    check(not vgaps, f"video timeline continuous: {len(vp)} frames, frame step {vd*1000:.1f} ms, gaps {['%.3f' % g for g in vgaps]}")
    expected_frames = round(expected * 30)
    check(abs(len(vp) - expected_frames) <= 2, f"video frame count {len(vp)} (expected ~{expected_frames})")
    if a:
        ap = packet_times(out_path, "a:0")
        ad = statistics.median([b[0] - a_[0] for a_, b in zip(ap, ap[1:])])
        agaps = [(round(a_[0], 3), round(b[0] - a_[0], 3)) for a_, b in zip(ap, ap[1:]) if b[0] - a_[0] > ad * 1.6 or b[0] - a_[0] <= 0]
        check(ap[0][0] <= 0.05, f"audio starts at {ap[0][0]:.3f}s")
        check(not agaps, f"audio timeline continuous: {len(ap)} packets, step {ad*1000:.1f} ms, gaps/overlaps {agaps}")
        v_end = vp[-1][0] + vd
        a_end = ap[-1][0] + ad
        check(abs(v_end - a_end) <= 0.1, f"audio ends with video (video {v_end:.3f}s, audio {a_end:.3f}s)")

    err = run("ffmpeg", "-v", "error", "-i", out_path, "-f", "null", "-").stderr.strip()
    check(not err, "decodes without errors" + ("" if not err else ": " + err.splitlines()[0]))

    flashes = flash_times(out_path)
    beeps = beep_times(out_path, dur)
    exp_flashes = [s + 1.0 for s in starts]
    exp_beeps = [s + 1.0 for s, has in zip(starts, in_has_audio) if has]
    check(len(flashes) == len(exp_flashes) and all(abs(f - e) <= SYNC_TOLERANCE for f, e in zip(flashes, exp_flashes)),
          f"flashes at {['%.3f' % f for f in flashes]}, expected {['%.3f' % e for e in exp_flashes]}")
    check(len(beeps) == len(exp_beeps) and all(abs(b - e) <= SYNC_TOLERANCE for b, e in zip(beeps, exp_beeps)),
          f"beeps at {['%.3f' % b for b in beeps]}, expected {['%.3f' % e for e in exp_beeps]}")
    offsets = [min((b - f for f in flashes), key=abs) for b in beeps] if flashes else []
    check(bool(offsets) and all(abs(o) <= SYNC_TOLERANCE for o in offsets),
          f"A/V sync per clip (beep minus flash): {['%+.0f ms' % (o * 1000) for o in offsets]}")

    for i, s in enumerate(starts):
        frame = gray_frame(out_path, s + 2.0, dw, dh)
        b = bars_of(frame, dw, dh)
        kind = case["bars"].get(i)
        black = lambda x: x < 12
        if kind == "tb":
            ok = black(b["top"]) and black(b["bottom"]) and not black(b["left"]) and not black(b["right"]) and not black(b["centre"])
        elif kind == "lr":
            ok = black(b["left"]) and black(b["right"]) and not black(b["top"]) and not black(b["bottom"]) and not black(b["centre"])
        else:
            ok = not any(black(b[k]) for k in ("top", "bottom", "left", "right"))
        desc = {"tb": "black bars top/bottom", "lr": "black bars left/right"}.get(kind, "no bars")
        check(ok, f"clip {i + 1} ({case['clips'][i]}): {desc} (top {b['top']:.0f}, bottom {b['bottom']:.0f}, left {b['left']:.0f}, right {b['right']:.0f}, centre {b['centre']:.0f})")

    size = os.path.getsize(out_path)
    in_size = sum(os.path.getsize(p) for p in inputs)
    ratio = size / in_size
    if case["copy_only"]:
        check(abs(ratio - 1) <= 0.03, f"size {size/1e6:.2f} MB vs inputs {in_size/1e6:.2f} MB ({ratio:.3f}x)")
    else:
        # Converted clips are encoded at the copied clips' bitrate; encoders overshoot some
        # (the emulator's software encoder by up to ~2x at these low test bitrates).
        check(ratio <= 2.0, f"size {size/1e6:.2f} MB vs inputs {in_size/1e6:.2f} MB ({ratio:.3f}x, converted clip didn't balloon)")
    return failures, notes


def main():
    clips_dir, out_dir = sys.argv[1], sys.argv[2]
    total_failures = 0
    for name, case in CASES.items():
        path = os.path.join(out_dir, name)
        print(f"== {name}")
        if not os.path.exists(path):
            print("FAIL missing")
            total_failures += 1
            continue
        failures, notes = check_case(name, case, clips_dir, path)
        for line in notes + failures:
            print("  " + line)
        total_failures += len(failures)
    print(f"\n{total_failures} failure(s)")
    sys.exit(1 if total_failures else 0)


if __name__ == "__main__":
    main()
