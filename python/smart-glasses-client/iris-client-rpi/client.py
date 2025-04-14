from tts import speak
speak("Loading modules...")
from collections import defaultdict
from ultralytics import YOLO
import socket
import base64
from picamera2 import Picamera2
import cv2
import speech_recognition as sr
import re
import json
from threading import Thread, Lock
from time import sleep
from os import _exit
from command_classifier import get_command
from vqa import vqa
from nlp_utils_client import user_llm_query

def save_server_ip(ip_address):
    """Saves the given IP address to a file."""
    with open("server_ip", "w") as file:
        file.write(ip_address)

def load_server_ip():
    """Loads and returns the IP address from the file."""
    try:
        with open("server_ip", "r") as file:
            return file.read().strip()
    except FileNotFoundError:
        return None

host = load_server_ip()
port = 55555
clientsocket = None

try:
    picam2 = Picamera2()
    picam2.preview_configuration.main.size = (1280, 720)
    picam2.preview_configuration.main.format = "RGB888"
    picam2.preview_configuration.align()
    picam2.configure("preview")
    picam2.start()
except Exception:
    print("Can't access camera!")
    speak("I can't access the camera!")
    picam2 = None

model = YOLO("/home/radhan/iris-client-rpi/yolo_model.pt")
connected = False

sending_lock = Lock()

try:
   my_ipaddress = (([ip for ip in socket.gethostbyname_ex(socket.gethostname())[2] if not ip.startswith("127.")] or [[(s.connect(("8.8.8.8", 53)), s.getsockname()[0], s.close()) for s in [socket.socket(socket.AF_INET, socket.SOCK_DGRAM)]][0][1]]) + ["no IP found"])[0]
except:
   my_ipaddress = None

def send_data(str):
    global clientsocket, connected
    if clientsocket and connected:
        sending_lock.acquire()
        try:
            clientsocket.send(str.encode())
        except e:
            print("Error while sending data")
        finally:
            sending_lock.release()

def extract_json_objects(data, leftover):
    """
    Extract complete JSON objects from the received data.
    Any leftover incomplete data is returned for use in the next iteration.
    """
    # Append new data to the leftover
    buffer = leftover + data.decode('utf-8')
    buffer = sanitize_json(buffer)
    json_objects = []
    start_idx = 0

    while True:
        try:
            # Attempt to decode a JSON object
            obj, end_idx = json.JSONDecoder().raw_decode(buffer[start_idx:])
            json_objects.append(obj)  # Add decoded object to the list
            start_idx += end_idx  # Move the start index forward
        except json.JSONDecodeError:
            leftover = buffer[start_idx:]
            break

    return json_objects, leftover

def sanitize_json(json_str):
    """
    Replace invalid control characters with escaped versions to make JSON valid.
    """
    # Replace unescaped control characters (like newlines within strings)
    return re.sub(r'(?<!\\)([\x00-\x1F])', lambda match: f'\\u{ord(match.group(0)):04x}', json_str)

def receive_heartbeat_ack():
    pass

def heartbeat_loop():
    while True:
        send_data('{"command":"heartbeat"}')
        sleep(0.5)

def receive_message(json):
    if "heartbeat_ack" in json:
        receive_heartbeat_ack()
    if "heartbeat" in json:
        send_data('{"command":"heartbeat_ack"}')
    if "message" in json:
        print(f"[ msg ]\n{json['message']}\n\n ", end='')
    if "image_caption" in json:
        print(f"[ image caption ]\n{json['image_caption']}\n\n", end='')
        image_caption = json['image_caption']
        speak(f"I see {image_caption}")
    if "found_face" in json:
        if json["found_face"] == False:
            print("Cannot recognize face")
            speak("I can't recognize this person")
        else:
            print(f"[ face recognition ]\n{json['face_name']}\n\n", end='')
            speak(f"This is {json['face_name']}")
    if "ocr_text" in json:
        print(f"[ ocr ]\n{json['ocr_text']}\n\n", end='')

def receive_loop():
    global clientsocket
    global connected
    while True:

        clientsocket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            clientsocket.connect((host, port))
            connected = True
        except:
            connected = False
        if connected:    
            try:
                print(f"Host: {host}, port: {port}")
                heartbeat_counter = 0
                leftover = ''
                while True:
                    data = clientsocket.recv(1024)  # Receive data from socket
                    if not data:
                        break  # Connection closed

                    json_objects, leftover = extract_json_objects(data, leftover)

                    for obj in json_objects:
                        receive_message(obj)

            except Exception as e:
                print(f"Error: {e}")

            finally:
                # Close the connection with the client
                clientsocket.close()
                print(f"Connection with {host} closed.")

def scene_description():
    img = picam2.capture_array()
    jpg_img = cv2.imencode('.jpg', img)
    b64_string = base64.b64encode(jpg_img[1]).decode('utf-8')
    str = '{"command":"image_caption","images":["%s"]}'%b64_string
    send_data(str)

def face_register(name):
    img = picam2.capture_array()
    jpg_img = cv2.imencode('.jpg', img)
    b64_string = base64.b64encode(jpg_img[1]).decode('utf-8')
    str = '{"command":"add_face","images":["%s"],"extra_instructions":"%s"}'%(b64_string,name)
    speak(f"Registered face as {name}")
    send_data(str)
    
def face_recognize():
    img = picam2.capture_array()
    jpg_img = cv2.imencode('.jpg', img)
    b64_string = base64.b64encode(jpg_img[1]).decode('utf-8')
    str = '{"command":"face_recognition","images":["%s"]}'%b64_string
    #print(str)
    send_data(str)
   
def run_vqa(question):
    img = picam2.capture_array()
    answer = vqa(img, question)
    print("VQA: ", answer)
    speak(answer)

def ocr():
    img = picam2.capture_array()
    jpg_img = cv2.imencode('.jpg', img)
    b64_string = base64.b64encode(jpg_img[1]).decode('utf-8')
    str = '{"command":"ocr","images":["%s"]}'%b64_string
    send_data(str)
    
def llm(prompt):
    looking_message()
    response = user_llm_query(prompt)
    speak(response)

def speech_recognition_loop():
    r = sr.Recognizer()
    with sr.Microphone(1) as source:
        while True:
            audio = r.listen(source)
            try:
                text = r.recognize_google(audio)
                run_command(text)
                print(text)
            except Exception as e:
                print(e)

def cli_loop():
    while True:
        command = input("> ")
        run_command(command)
        
def extract_ip(sentence):
    # Find all numbers in the sentence
    numbers = re.findall(r'\d+', sentence)
    

    # Ensure there are exactly 4 parts to form an IP address
    if len(numbers) == 4:
        server_ip = ".".join(numbers)
        print("Trying to connect...")
        speak("Connecting to "+server_ip)
        return server_ip
    else:
        repeat_message()
        return None
        
def looking_message():
    speak("Just a moment.")
def repeat_message():
    speak("I'm sorry, could you repeat that?")
        
def run_command(text):
    global host
    text = text.lower()
    if "iris" in text:
        if "connect to" in text or "connects to" in text:
            host = extract_ip(text)
            if host:
                save_server_ip(host)
        if "this is" in text:
            name = text.split("this is",1)[1]
            if name:
                face_register(name)
            else:
                repeat_message()
        else:
            command = get_command(text)
            if command == "none":
                return
            if command == "quit":
                print("Exiting...")
                speak("Bye bye")
                _exit(0)
            elif command == "llm":
                llm(text)
            elif command == "image_captioning":
                scene_description()
                looking_message()
            elif command == "face_recognize":
                face_recognize()
                looking_message()
            elif command == "ocr":
                ocr()
            elif command == "vqa":
                run_vqa(text.replace("iris",""))
            elif command == "object_detection":
                object_detection()
                speak("Looking for objects")
            elif command == "get_ip":
                announce_ip_address()


object_detection_counter = 0

def object_detection_loop():
    global object_detection_counter

    while object_detection_counter > 0:

        # Dictionary to accumulate confidence scores for each class
        cumulative_confidences = defaultdict(float)

        # Run YOLO model 10 times
        for _ in range(2):
            image = picam2.capture_array()
            h, w = image.shape[:2]
            scale = 640 / max(w, h)  # Scale so the largest dimension is 640
            new_w, new_h = int(w * scale), int(h * scale)
            resized_image = cv2.resize(image, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
            blurred_img = cv2.GaussianBlur(img, (5, 5), 0)
            results = model(blurred_img, conf=0.2, verbose=False)

            if results[0].boxes and len(results[0].boxes) > 0:
                # Extract detected class indices and confidence scores
                confidences = results[0].boxes.conf.tolist()  # Convert tensor to list
                class_indices = results[0].boxes.cls.tolist()  # Convert tensor to list

                # Get class names
                detected_classes = results[0].names

                # Accumulate confidence scores
                for cls_idx, conf in zip(class_indices, confidences):
                    class_name = detected_classes[int(cls_idx)]
                    cumulative_confidences[class_name] += conf

        if cumulative_confidences:
            # Find the class with the highest cumulative confidence
            best_class = max(cumulative_confidences, key=cumulative_confidences.get)
            best_confidence = cumulative_confidences[best_class]

            print(f"Class with highest cumulative confidence: {best_class} ({best_confidence:.2f})")
            if '/' in best_class:
                string = best_class.split('/', 1)[0]
            else:
                string = best_class
            speak(f"I see {string}")
        object_detection_counter -= 1
        sleep(0.5)


def object_detection(iterations=50):
    global object_detection_counter
    if object_detection_counter == 0:
        object_detection_counter = iterations
        t = Thread(target=object_detection_loop, args=[])
        t.start()
    else:
        object_detection_counter = iterations

def announce_ip_address():
    speak(f"My IP address is: {my_ipaddress}")

if __name__ == "__main__":
    receive_thread = Thread(target=receive_loop, args=[])
    heartbeat_thread = Thread(target=heartbeat_loop, args=[])
    cli_thread = Thread(target=cli_loop, args=[])
    speech_recognition_thread = Thread(target=speech_recognition_loop, args=[])

    receive_thread.start()
    heartbeat_thread.start()
    cli_thread.start()
    speech_recognition_thread.start()
    announce_ip_address()
