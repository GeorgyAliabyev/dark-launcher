#!/usr/bin/env python3
"""Synthesises Dark Launcher's feedback sounds.

The set is foley, not melody: knocks, plops, a paper swish, something dropped into a
bin. Every sound is a transient — noise, a fast pitch drop, a short metal ring — so
nothing sits on a musical note and nothing drones long enough to get on the nerves on
the twentieth folder of the evening.

Peak is normalised to about -7 dBFS: percussive sounds read quieter than sustained
tones, so this sits at the same felt loudness as the old set.

Output: 44.1 kHz mono OGG (Vorbis, quality 3) in core/data/src/main/res/raw/.
Run: python3 tools/synth_feedback_sounds.py
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "core", "data", "src", "main", "res", "raw")
RNG = np.random.default_rng(20261118)


def t_of(ms):
    return np.arange(int(SR * ms / 1000)) / SR


def noise(n):
    return RNG.normal(0, 1, n)


def _mask(sig, gain_of_freq):
    spec = np.fft.rfft(sig)
    freqs = np.fft.rfftfreq(len(sig), 1 / SR)
    return np.fft.irfft(spec * gain_of_freq(freqs), len(sig))


def band(sig, f0, q=3.0):
    """A gaussian band around f0, q = f0 / bandwidth. Cheap, zero-phase, no scipy."""
    return _mask(sig, lambda f: np.exp(-0.5 * ((f - f0) / (f0 / q / 2)) ** 2))


def lowpass(sig, f):
    return _mask(sig, lambda x: 1.0 / (1.0 + (x / f) ** 4))


def highpass(sig, f):
    return _mask(sig, lambda x: 1.0 / (1.0 + (f / np.maximum(x, 1.0)) ** 4))


def fade_in(sig, ms=1.0):
    n = max(int(SR * ms / 1000), 1)
    sig = sig.copy()
    sig[:n] *= np.linspace(0.0, 1.0, n)
    return sig


def thump(ms, f_start, f_end, decay, amp=1.0):
    """A falling sine: the body of an impact. Exponential glide, so it reads as a hit,
    not as a note sliding down."""
    t = t_of(ms)
    freqs = f_start * (f_end / f_start) ** (t / t[-1])
    phase = 2 * np.pi * np.cumsum(freqs) / SR
    return amp * np.sin(phase) * np.exp(-t / decay)


def click(ms=8.0, amp=1.0, brightness=2500.0):
    t = t_of(ms)
    return amp * lowpass(noise(len(t)), brightness) * np.exp(-t / 0.0018)


def ring(ms, freqs, decay, amps=None):
    """Inharmonic partials of a struck object: a bin, a tin, a wooden box."""
    t = t_of(ms)
    amps = amps or [1.0 / (i + 1) for i in range(len(freqs))]
    out = np.zeros(len(t))
    for f, a in zip(freqs, amps):
        out += a * np.sin(2 * np.pi * f * t + RNG.uniform(0, 2 * np.pi))
    return out * np.exp(-t / decay)


def swish(ms, f_from, f_to, amp=1.0, q=1.6):
    """Noise through a band that moves: paper, cloth, a hand brushing the screen."""
    t = t_of(ms)
    src = noise(len(t))
    bands = 8
    out = np.zeros(len(t))
    for i in range(bands):
        f = f_from * (f_to / f_from) ** (i / (bands - 1))
        w = np.zeros(len(t))
        n = len(t) // bands
        w[i * n:(i + 1) * n] = np.hanning(n)[: len(w[i * n:(i + 1) * n])]
        out += band(src, f, q) * w
    shape = np.sin(np.pi * np.linspace(0, 1, len(t))) ** 1.5
    return amp * out * shape


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[: len(p)] += p
    return out


def at(sig, start_ms, total_ms):
    out = np.zeros(int(SR * total_ms / 1000))
    i = int(SR * start_ms / 1000)
    out[i:i + len(sig)] += sig
    return out


def normalise(sig, peak_db=-7.0):
    peak = np.max(np.abs(sig)) or 1.0
    return sig / peak * (10 ** (peak_db / 20))


def write(name, sig):
    sig = normalise(fade_in(sig, 0.5))
    os.makedirs(OUT, exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        path = tmp.name
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((sig * 32767).astype("<i2").tobytes())
    ogg = os.path.join(OUT, name + ".ogg")
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-c:a", "libvorbis", "-q:a", "3", "-ac", "1", ogg],
        check=True,
    )
    os.unlink(path)
    print(f"{ogg}  {len(sig) / SR * 1000:.0f} ms  {os.path.getsize(ogg)} B")


# select — a dry wooden tick. Something under the finger, no note at all.
write(
    "feedback_select",
    mix(
        click(6, 0.9, 3200),
        thump(18, 420, 260, 0.004, 0.5),
        ring(22, [1700, 2900], 0.004, [0.18, 0.1]),
    ),
)

# place — a pebble settling: a soft plop with a breath of felt under it.
write(
    "feedback_place",
    mix(
        thump(80, 300, 95, 0.028, 1.0),
        click(8, 0.35, 1800),
        lowpass(noise(int(SR * 0.05)), 900) * np.exp(-t_of(50) / 0.008) * 0.3,
    ),
)

# confirm — a cork pop: short, round, unmistakably "done".
write(
    "feedback_confirm",
    mix(
        thump(70, 220, 60, 0.022, 1.0),
        click(6, 0.6, 2600),
        ring(60, [950, 1500], 0.012, [0.12, 0.06]),
    ),
)

# folder_create — a knock on a wooden box, then the box closing: two beats, no melody.
pop = mix(thump(60, 380, 150, 0.018, 0.9), click(6, 0.45, 2400), ring(70, [700, 1180, 1830], 0.02, [0.5, 0.28, 0.14]))
snap = mix(thump(90, 200, 70, 0.03, 1.0), click(7, 0.4, 1500), ring(120, [430, 810, 1320], 0.035, [0.6, 0.3, 0.15]))
write("feedback_folder_create", mix(at(pop, 0, 260), at(snap, 95, 260)))

# folder_open — paper sliding aside: a short swish with a soft end.
write(
    "feedback_folder_open",
    mix(
        swish(150, 1400, 4200, 0.85),
        at(thump(60, 260, 120, 0.02, 0.35), 70, 150),
    ),
)

# move — two wooden clicks, the second a touch lower: picked up, put down.
write(
    "feedback_move",
    mix(
        at(mix(click(6, 0.8, 3000), thump(24, 480, 300, 0.006, 0.55)), 0, 150),
        at(mix(click(7, 0.75, 2600), thump(34, 380, 200, 0.009, 0.7)), 62, 150),
    ),
)

# delete — something dropped into a bin: paper hit, a tin ring, a low landing.
bin_hit = mix(
    highpass(noise(int(SR * 0.05)), 2200) * np.exp(-t_of(50) / 0.009) * 0.4,
    ring(260, [980, 1540, 2360, 3520], 0.09, [0.9, 0.6, 0.4, 0.22]),
    at(thump(140, 160, 52, 0.055, 1.0), 45, 260),
    at(thump(60, 420, 240, 0.012, 0.35), 0, 260),
)
write("feedback_delete", mix(bin_hit, at(swish(60, 2200, 4200, 0.2), 0, 260)))

# error — a dull double knock against a hollow body: refused, no alarm in it.
knock = mix(
    thump(110, 170, 62, 0.035, 1.0),
    lowpass(noise(int(SR * 0.05)), 700) * np.exp(-t_of(50) / 0.01) * 0.45,
    ring(140, [310, 520], 0.03, [0.25, 0.12]),
)
write("feedback_error", mix(at(knock, 0, 300), at(knock * 0.75, 135, 300)))
