"""生成 NeuralPulse 联调用的测试音频。

默认输出 120 BPM 的 60 秒节拍曲（可 --bpm 覆盖）：
- 底鼓：每拍一次，55Hz 正弦 + 快速衰减（低频能量强，验证 bass/节拍）
- 踩镲：每半拍一次，白噪声高频爆发（验证 treble/色相偏移）
- 贝斯线：A1-A2 交替的持续低音（验证中低频）
仅用标准库（wave/math/random/struct），无第三方依赖。

用法：python tools/make_test_beat.py [--bpm 120] [--seconds 60] [--out out/beat120.wav]
"""
import argparse
import math
import os
import random
import struct
import wave

SR = 44100


def render(bpm: float, seconds: float):
    beat = 60.0 / bpm
    total = int(SR * seconds)
    mix = [0.0] * total
    rng = random.Random(120)

    # 底鼓 + 踩镲事件表
    kicks, hats = [], []
    t = 0.0
    while t < seconds:
        kicks.append(t)
        hats.append(t + beat / 2)
        t += beat

    def add(start, dur, fn, gain):
        i0 = int(start * SR)
        for i in range(max(0, i0), min(total, int((start + dur) * SR))):
            ph = (i - i0) / SR
            mix[i] += fn(ph) * gain

    # 底鼓：55Hz 衰减正弦
    for k in kicks:
        add(k, 0.22, lambda p: math.sin(2 * math.pi * 55 * p) * math.exp(-p * 18), 0.85)
    # 踩镲：带通感高频噪声（白噪*快衰减 + 6kHz 位移调制）
    for h in hats:
        add(h, 0.06, lambda p: (rng.random() * 2 - 1) * math.exp(-p * 70)
            * (0.6 + 0.4 * math.sin(2 * math.pi * 6000 * p)), 0.30)
    # 贝斯：A1/A2 交替，两拍一换
    notes = [55.0, 55.0, 82.5, 110.0]
    t = 0.0
    idx = 0
    while t < seconds:
        f = notes[idx % len(notes)]
        add(t, beat * 1.8,
            lambda p, f=f: math.sin(2 * math.pi * f * p) * 0.6
            + 0.25 * math.sin(2 * math.pi * f * 2 * p), 0.32)
        t += beat * 2
        idx += 1

    # 软限幅 + 归一
    peak = max(1e-6, max(abs(v) for v in mix))
    scale = 0.92 / peak
    return [max(-1.0, min(1.0, v * scale)) for v in mix]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bpm", type=float, default=120.0)
    ap.add_argument("--seconds", type=float, default=60.0)
    ap.add_argument("--out", default=os.path.join("out", "beat120.wav"))
    args = ap.parse_args()

    data = render(args.bpm, args.seconds)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with wave.open(args.out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(v * 32767)) for v in data))
    print("wrote", args.out, f"({args.bpm} BPM, {args.seconds}s)")


if __name__ == "__main__":
    main()
