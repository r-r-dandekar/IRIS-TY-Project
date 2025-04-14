import numpy as np
import sounddevice as sd

def generate_sound(frequency=440, duration=1, sample_rate=44100):
    t = np.linspace(0, duration, int(sample_rate * duration), False)
    wave = 0.5 * np.sin(2 * np.pi * frequency * t)
    sd.play(wave, samplerate=sample_rate)
    sd.wait()

# Example: Play a 440 Hz beep for 1 second
generate_sound()

