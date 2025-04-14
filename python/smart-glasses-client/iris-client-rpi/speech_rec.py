import speech_recognition as sr

def speech_recognition_loop():
    r = sr.Recognizer()
    with sr.Microphone(1) as source:
        while True:
            audio = r.listen(source)
            try:
                text = r.recognize_google(audio)
                print(text)
			except Exception as e:
				print("Sorry, could not understand audio")
				print(e)
