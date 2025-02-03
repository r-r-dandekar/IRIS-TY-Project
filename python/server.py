from face_recognition import find_face, add_faces
from barcode_qrcode import detect_barcode_or_qr, fetch_online_info
from nlp_utils import combine_descriptions, clean_ocr_output, summarize_barcode_data
import socket
import cv2
import numpy as np
import base64
from object_detection import show_results_from_opencv_image as detect_objects
import json
from image_captioning import predict_step_from_rgb_images
import time
from sys import stdout
import re
from ocr_utils import image_to_string
from deepface import DeepFace
from threading import Lock, Thread
from os import _exit
import threading

# Server settings
HOST = '0.0.0.0'  # Bind to all network interfaces
PORT = 55555      # Port to listen on
client_socket = None

heartbeat_counter = 0
heartbeat_counter_limit = 150

sending_lock = Lock()
heartbeat_counter_lock = Lock()

def send_to_client(data):
    if client_socket:
        sending_lock.acquire()
        client_socket.send(data)
        sending_lock.release()
    else:
        print("Error: No client connected!")
        

def sanitize_json(json_str):
    """
    Replace invalid control characters with escaped versions to make JSON valid.
    """
    # Replace unescaped control characters (like newlines within strings)
    return re.sub(r'(?<!\\)([\x00-\x1F])', lambda match: f'\\u{ord(match.group(0)):04x}', json_str)

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


def ocr(json_data):
        base64_strings = json_data["images"]
        extra_instructions = json_data["extra_instructions"]
        print("extra_instructions: "+extra_instructions)
        stdout.flush()

        ocr_output = []
        for base64_string in base64_strings:
            image_data = base64.b64decode(base64_string)
            nparr = np.frombuffer(image_data, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
            text = image_to_string(image)
            ocr_output.append(text)

        text = clean_ocr_output(ocr_output, extra_instructions=extra_instructions)
        results={"ocr_text":text}
        print(results)
        json_str = json.dumps(results)
        send_to_client(json_str.encode())


def face_recognition(json_data):
        base64_strings = json_data["images"]
        extra_instructions = json_data["extra_instructions"]
        print("extra_instructions: "+extra_instructions)
        stdout.flush()

        name = ''
        for base64_string in base64_strings:
            image_data = base64.b64decode(base64_string)
            nparr = np.frombuffer(image_data, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
            name = find_face(image)
            if name != None:
                break

        if name:
            results={"face_name":name, "found_face":True}
        else:
            results={"face_name":"", "found_face":False}
        print(results)
        json_str = json.dumps(results)
        send_to_client(json_str.encode())

def image_caption(json_data):

        print("Got JSON data")

        images = []
        base64_strings = json_data["images"]
        extra_instructions = json_data["extra_instructions"]
        print("extra_instructions: "+extra_instructions)
        stdout.flush()

        if len(base64_strings) > 1:

            for base64_string in base64_strings:
                image_data = base64.b64decode(base64_string)
                nparr = np.frombuffer(image_data, np.uint8)
                image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
                # image = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
                images.append(image)

            start_time = time.time()
            descriptions = predict_step_from_rgb_images(images)
            end_time = time.time()
            execution_time = end_time - start_time
            print(f"Time to generate descriptions: {execution_time}")

            caption = combine_descriptions(descriptions, extra_instructions=extra_instructions)

        else:
            image_data = base64.b64decode(base64_strings[0])
            nparr = np.frombuffer(image_data, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            images.append(image)

            start_time = time.time()
            descriptions = predict_step_from_rgb_images(images)
            end_time = time.time()
            execution_time = end_time - start_time
            print(f"Time to generate description: {execution_time}")

            caption = descriptions[0]


        results = {"image_caption":caption}
        print(results)
        json_str = json.dumps(results)
        send_to_client(json_str.encode())

def count_objects(json_data):
    print("Counting objects...")

    images = []
    base64_strings = json_data["images"]
    extra_instructions = json_data["extra_instructions"]
    print("extra_instructions: "+extra_instructions)
    stdout.flush()

    # For now, only use one image
    # for base64_string in base64_strings:
    #     image_data = base64.b64decode(base64_string)
    #     nparr = np.frombuffer(image_data, np.uint8)
    #     image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    #     image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    #     images.append(image)
    
    if base64_strings:
        base64_string = base64_strings[0]
        image_data = base64.b64decode(base64_string)
        nparr = np.frombuffer(image_data, np.uint8)
        image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)

        start_time = time.time()
        detected_objects = detect_objects(image)
        end_time = time.time()
        execution_time = end_time - start_time
        print(f"Time to count objects: {execution_time}")
        
        results = {"objects":detected_objects}
        print(results)
        json_str = json.dumps(results)
        send_to_client(json_str.encode())

def add_face(json_data):
        print("Got JSON data")

        images = []
        base64_strings = json_data["images"]
        extra_instructions = json_data["extra_instructions"]
        name = extra_instructions
        print("extra_instructions: "+extra_instructions)
        stdout.flush()

        for base64_string in base64_strings:
            image_data = base64.b64decode(base64_string)
            nparr = np.frombuffer(image_data, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            # image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            # image = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
            images.append(image)

        add_faces(images, name)
        
        results = {"add_face":"success"}
        print(results)
        json_str = json.dumps(results)
        send_to_client(json_str.encode())


def barcode(json_data):
    # Run the function in a new thread because the barcode information part
    # may make an API call which blocks for a long time, which would otherwise
    # pause heartbeat messages and cause the connection to be terminated
    thread = threading.Thread(target=barcode_new_thread, args=(json_data,))
    thread.start()

def barcode_new_thread(json_data):
    print("Looking for a bar code or QR code")

    barcode_raw_value = json_data["barcode_raw_value"]
    extra_instructions = json_data["extra_instructions"]
    print("extra_instructions: "+extra_instructions)

    print(barcode_raw_value)
    stdout.flush()

    barcode_data = fetch_online_info(barcode_raw_value)

    print(barcode_data)
    if barcode_data:
        summary = summarize_barcode_data(barcode_data, extra_instructions=extra_instructions)
        barcode_data = summary
    else:
        barcode_data = "No data found"

    results = {"barcode":barcode_raw_value,"barcode_data":barcode_data}
    print(results)
    json_str = json.dumps(results)
    send_to_client(json_str.encode())


    # OLD METHOD: USE AN IMAGE
    # # For now, only use one image    
    # if base64_strings:
    #     base64_string = base64_strings[0]
    #     image_data = base64.b64decode(base64_string)
    #     nparr = np.frombuffer(image_data, np.uint8)
    #     image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    #     image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)

    #     results = detect_barcode_or_qr(image)
        
    #     if results:
    #         print(results)
    #         barcode_data = results['barcode_data']
    #         if barcode_data:
    #             summary = summarize_barcode_data(barcode_data, extra_instructions=extra_instructions)
    #             results['barcode_data'] = summary
    #         else:
    #             results['barcode_data'] = "No data found"
    #     else:
    #         results = {"message":"I couldn't find any barcode or qr code"}
    #     json_str = json.dumps(results)
    #     send_to_client(json_str.encode())

        
def run_command(json):
    # print("command: "+json["command"])
    if json["command"]=="image_caption":
        image_caption(json)
    elif json["command"]=="ocr":
        ocr(json)
    elif json["command"]=="face_recognition":
        face_recognition(json)
    elif json["command"]=="add_face":
        add_face(json)
    elif json["command"]=="count_objects":
        count_objects(json)
    elif json["command"]=="barcode":
        barcode(json)
    elif json["command"]=="heartbeat_ack":
        receive_heartbeat_ack()
    elif json["command"]=="heartbeat":
        send_heartbeat_ack()

def send_heartbeat_ack():
    send_to_client(('{"heartbeat_ack":"hello"}').encode())

def receive_heartbeat_ack():
    global heartbeat_counter
    heartbeat_counter_lock.acquire()
    heartbeat_counter = 0
    heartbeat_counter_lock.release()

def heartbeat(sleep_seconds=0.5):
    global heartbeat_counter
    while(True):
        if client_socket and is_socket_connected(client_socket):
        # if True:
            msg = {"heartbeat":"hello"}
            json_str = json.dumps(msg)
            send_to_client(json_str.encode())
            heartbeat_counter_lock.acquire()
            heartbeat_counter += 1
            if heartbeat_counter > heartbeat_counter_limit:
                print("Terminating connection with client: No response to heartbeats")
                try:
                    client_socket.close()
                except Exception:
                    print("Something went wrong when terminating connection")
            heartbeat_counter_lock.release()
        time.sleep(sleep_seconds)

def is_socket_connected(sock):
    try:
        sock.getpeername()
        return True
    except socket.error:
        return False
    
def console():
    while True:
        str = input()
        if str == 'quit' or str == 'exit':
            _exit(1)

if __name__=="__main__":
    # Create a TCP/IP socket
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

    # Bind the socket to the address and port
    server_socket.bind((HOST, PORT))

    heartbeat_thread = Thread(target=heartbeat)
    heartbeat_thread.start()
    
    console_thread = Thread(target=console)
    console_thread.start()

    # Enable the server to accept connections (max 1 connection in this case)
    server_socket.listen(1)
    print(f"Server listening on {HOST}:{PORT}...")

    while True:
        # Accept a connection
        client_socket, client_address = server_socket.accept()
        print(f"Connection from {client_address}")

        heartbeat_counter = 0

        try:
            leftover = ''
            while True:
                data = client_socket.recv(1024)  # Receive data from socket
                if not data:
                    break  # Connection closed

                json_objects, leftover = extract_json_objects(data, leftover)

                # print("hellloooo "+leftover)

                for obj in json_objects:
                    run_command(obj)

        except Exception as e:
            print(f"Error: {e}")
        
        finally:
            # Close the connection with the client
            client_socket.close()
            print(f"Connection with {client_address} closed.")
