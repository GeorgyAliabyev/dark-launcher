#!/usr/bin/env python3
"""Synthesises Dark Launcher's feedback sounds.

Every sound is built from sine partials with short attack and exponential decay, so the
whole set shares one voice: soft, low-mid, never piercing. Peak is normalised to about
-9 dBFS — the phone's media volume does the rest, and a notice should never startle.

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


def env(t, attack, decay):
    a = np.clip(t / max(attack, 1e-4), 0.0, 1.0)
    return a * np.exp(-t / decay)


def tone(t, freq, amp=1.0, phase=0.0):
    return amp * np.sin(2 * np.pi * freq * t + phase)


def glide(t, f0, f1, amp=1.0):
    """A sine whose frequency moves linearly from f0 to f1 (phase-continuous)."""
    freqs = np.linspace(f0, f1, len(t))
    phase = 2 * np.pi * np.cumsum(freqs) / SR
    return amp * np.sin(phase)


def silence(ms):
    return np.zeros(int(SR * ms / 1000))


def concat(*parts):
    return np.concatenate(parts)


def place(sig, start_ms, total_ms):
    out = np.zeros(int(SR * total_ms / 1000))
    i = int(SR * start_ms / 1000)
    out[i:i + len(sig)] += sig
    return out


def normalise(sig, peak_db=-9.0):
    peak = np.max(np.abs(sig)) or 1.0
    return sig / peak * (10 ** (peak_db / 20))


def write(name, sig):
    sig = normalise(sig)
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


# select — a 25 ms tick, high but quiet: the "you touched something" of a TalkBack-style surface.
t = np.arange(int(SR * 0.025)) / SR
write("feedback_select", tone(t, 1500, env(t, 0.0008, 0.006)) + tone(t, 3000, 0.25 * env(t, 0.0008, 0.004)))

# place — a soft 880 Hz chime: an icon landed on a cell.
t = np.arange(int(SR * 0.12)) / SR
write("feedback_place", tone(t, 880, env(t, 0.002, 0.045)) + tone(t, 1760, 0.18 * env(t, 0.002, 0.02)))

# confirm — a bright two-partial blip for "done".
t = np.arange(int(SR * 0.09)) / SR
write("feedback_confirm", tone(t, 1046.5, env(t, 0.0015, 0.035)) + tone(t, 2093, 0.2 * env(t, 0.0015, 0.015)))

# folder_create — E5 then B5, a small rising pair: a folder was made.
t1 = np.arange(int(SR * 0.1)) / SR
t2 = np.arange(int(SR * 0.16)) / SR
write(
    "feedback_folder_create",
    concat(
        tone(t1, 659.25, env(t1, 0.002, 0.05)),
        tone(t2, 987.77, env(t2, 0.002, 0.06)) + tone(t2, 1975.5, 0.15 * env(t2, 0.002, 0.03)),
    ),
)

# folder_open — a gentle descending sweep: the folder's grid unfolds.
t = np.arange(int(SR * 0.16)) / SR
write("feedback_folder_open", glide(t, 700, 420, env(t, 0.003, 0.045)) + glide(t, 1400, 840, 0.2 * env(t, 0.003, 0.02)))

# move — two clicks, the second higher: an item left one place and arrived at another.
c1 = np.arange(int(SR * 0.02)) / SR
c2 = np.arange(int(SR * 0.025)) / SR
write(
    "feedback_move",
    place(tone(c1, 1200, env(c1, 0.0005, 0.005)), 0, 100)
    + place(tone(c2, 1550, env(c2, 0.0005, 0.006)), 55, 100),
)

# delete — a low thud with a breath of noise: something is gone, no alarm in it.
t = np.arange(int(SR * 0.2)) / SR
rng = np.random.default_rng(7)
noise = rng.normal(0, 1, len(t)) * np.exp(-t / 0.012) * 0.25
write("feedback_delete", tone(t, 110, env(t, 0.002, 0.06)) + tone(t, 55, 0.4 * env(t, 0.002, 0.09)) + noise)

# error — a low double buzz: blocked or refused.
b = np.arange(int(SR * 0.07)) / SR
buzz = tone(b, 220, env(b, 0.003, 0.04)) + tone(b, 330, 0.3 * env(b, 0.003, 0.03))
write("feedback_error", concat(buzz, silence(45), buzz))
