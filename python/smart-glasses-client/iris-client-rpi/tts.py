import pyttsx3
import threading

# Global lock to ensure sequential execution
_speak_lock = threading.Lock()

def speak(text):
    """Speaks the given text, ensuring only one instance runs at a time."""
    with _speak_lock:  # Prevent overlapping speech
        try:
            engine = pyttsx3.init()
            engine.say(text)
            engine.runAndWait()
        except:
            print("Error during TTS")

if __name__ == "__main__":
    # Continuous loop to take input and speak
    while True:
        text = input("Enter text to speak (type 'exit' or 'quit' to stop): ")
        if text.lower() in ["exit", "quit"]:
            break
        threading.Thread(target=speak, args=(text,), daemon=True).start()

