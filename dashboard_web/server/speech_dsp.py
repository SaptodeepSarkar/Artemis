"""Pure-stdlib real-time speech post-processing for the LIVE mic stream.

The Artemis dashboard server runs on system python3.12 with NO numpy/scipy, so
this DSP is implemented with the standard library only. It is applied in the
FastAPI ws/live relay to channel-4 (PCM16 mono) frames BEFORE they reach the
browser, which:

  * reduces noise (sub-rumble hiss) and
  * focuses human speech (voice-formant presence boost),
  * plus an adaptive noise gate that suppresses inter-word ambience.

Stateless across frames except for small IIR filter state + a running RMS
noise floor estimate, so it is safe to call one PCM16 chunk at a time.
"""

import math
import struct


class _Biquad:
    """RBJ-style biquad, transposed direct form II (stable, no clamping)."""

    __slots__ = ("b0", "b1", "b2", "a1", "a2", "x1", "x2", "y1", "y2")

    def __init__(self, b0, b1, b2, a1, a2):
        self.b0, self.b1, self.b2 = b0, b1, b2
        self.a1, self.a2 = a1, a2
        self.x1 = self.x2 = self.y1 = self.y2 = 0.0

    @classmethod
    def highpass(cls, fs, fc, q=0.7071):
        w = 2.0 * math.pi * fc / fs
        c, s = math.cos(w), math.sin(w)
        alpha = s / (2.0 * q)
        a0 = 1.0 + alpha
        b0 = (1.0 + c) / 2.0
        b1 = -(1.0 + c)
        b2 = (1.0 + c) / 2.0
        a1 = -2.0 * c
        a2 = 1.0 - alpha
        return cls(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    @classmethod
    def lowpass(cls, fs, fc, q=0.7071):
        w = 2.0 * math.pi * fc / fs
        c, s = math.cos(w), math.sin(w)
        alpha = s / (2.0 * q)
        a0 = 1.0 + alpha
        b0 = (1.0 - c) / 2.0
        b1 = 1.0 - c
        b2 = (1.0 - c) / 2.0
        a1 = -2.0 * c
        a2 = 1.0 - alpha
        return cls(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    @classmethod
    def peaking(cls, fs, fc, q, db):
        A = 10.0 ** (db / 40.0)
        w = 2.0 * math.pi * fc / fs
        c, s = math.cos(w), math.sin(w)
        alpha = s / (2.0 * q)
        a0 = 1.0 + alpha / A
        b0 = 1.0 + alpha * A
        b1 = -2.0 * c
        b2 = 1.0 - alpha * A
        a1 = -2.0 * c
        a2 = 1.0 - alpha / A
        return cls(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    def step(self, x):
        y = (self.b0 * x + self.b1 * self.x1 + self.b2 * self.x2
             - self.a1 * self.y1 - self.a2 * self.y2)
        self.x2, self.x1 = self.x1, x
        self.y2, self.y1 = self.y1, y
        return y


class SpeechNoiseReducer:
    """Post-process int16 mono PCM to favour human speech."""

    def __init__(self, sample_rate=44100):
        self._hp = _Biquad.highpass(sample_rate, 170.0)      # sub rumble / mains
        self._hiss = _Biquad.lowpass(sample_rate, 5600.0)    # damp high hiss
        self._presence = _Biquad.peaking(sample_rate, 1900.0, 1.2, 4.0)  # voice
        # Adaptive noise gate state.
        self._floor = 0.02
        self._thresh_db = 12.0    # dB above floor to fully open
        self._floor_min = 0.0001

    def process_pcm16(self, pcm: bytes) -> bytes:
        n = len(pcm) // 2
        if n <= 0:
            return pcm
        s16 = struct.unpack("<%dh" % n, pcm)

        # --- RMS estimate for the gate -------------------------------------
        sumsq = 0.0
        for v in s16:
            sumsq += (v / 32768.0) ** 2
        rms = math.sqrt(sumsq / n) + 1e-9

        # Adaptive floor: rises up to signal, decays slowly when quiet.
        if rms > self._floor:
            self._floor += (rms - self._floor) * 0.5
        else:
            self._floor += (rms - self._floor) * 0.02
        if self._floor < self._floor_min:
            self._floor = self._floor_min

        # --- Soft gain gate (smooth knee / loudness > threshold) -----------
        db = 20.0 * math.log10(rms / self._floor + 1e-9)
        if rms <= self._floor:
            gain = 0.04
        else:
            g = db / self._thresh_db
            g = min(max(g, 0.04), 1.0)          # 0.04 floor -> up to 1.0
            # gentle map: keep some presence even below threshold
            gain = 0.4 + 0.6 * g if g < 1.0 else 1.0

        # --- Filter + re-encode --------------------------------------------------
        out = bytearray(n * 2)
        for i, v in enumerate(s16):
            x = v / 32768.0
            x = self._hp.step(x)
            x = self._hiss.step(x)
            x = self._presence.step(x)
            x *= gain
            if x > 1.0:
                x = 1.0
            elif x < -1.0:
                x = -1.0
            struct.pack_into("<h", out, i * 2, int(x * 32767.0))
        return bytes(out)


_singleton = SpeechNoiseReducer()


def process_mic_frame(pcm: bytes) -> bytes:
    """Post-process one PCM16 ch4 frame. Never raises; leaves it untouched on error."""
    try:
        return _singleton.process_pcm16(pcm)
    except Exception:
        return pcm


__all__ = ["SpeechNoiseReducer", "process_mic_frame"]