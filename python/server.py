from face_recognition import find_face
from nlp_utils import combine_descriptions, clean_ocr_output
import socket
import cv2
import numpy as np
import base64
# from object_detection import show_results_from_opencv_image 
import json
from image_captioning import predict_step_from_rgb_images
import time
from sys import stdout
import re
from ocr_utils import image_to_string
import random
import os
import string

# Server settings
HOST = '0.0.0.0'  # Bind to all network interfaces
PORT = 55555      # Port to listen on

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
        client_socket.send(json_str.encode())


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
        client_socket.send(json_str.encode())

def image_caption(json_data):

        print("Got JSON data")

        images = []
        base64_strings = json_data["images"]
        extra_instructions = json_data["extra_instructions"]
        print("extra_instructions: "+extra_instructions)
        stdout.flush()

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
        results = {"image_caption":caption}
        print(results)
        json_str = json.dumps(results)
        client_socket.send(json_str.encode())

def save_photos_with_unique_names(images, name):
    # Ensure the face_db directory exists
    folder_path = "face_db"
    if not os.path.exists(folder_path):
        os.makedirs(folder_path)

    saved_file_names = []  # Keep track of saved file names to ensure uniqueness

    for idx, image in enumerate(images):
        while True:
            # Generate a unique random number as a string
            random_number = ''.join(random.choices(string.digits, k=5))
            file_name = f"{name}_{random_number}.jpg"
            file_path = os.path.join(folder_path, file_name)

            # Check if the file name is unique
            if file_name not in saved_file_names and not os.path.exists(file_path):
                break

        # Save the image
        cv2.imwrite(file_path, image)
        saved_file_names.append(file_name)  # Add to the list of saved file names

        print(f"Image {idx + 1} saved as: {file_path}")

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
        
        save_photos_with_unique_names(images,name)

        results = {"add_face":"success"}
        print(results)
        json_str = json.dumps(results)
        client_socket.send(json_str.encode())
        
def run_command(json):
    print("command: "+json["command"])
    if json["command"]=="image_caption":
        image_caption(json)
    elif json["command"]=="ocr":
        ocr(json)
    elif json["command"]=="face_recognition":
        face_recognition(json)
    elif json["command"]=="add_face":
        add_face(json)

if __name__=="__main__":
    # Create a TCP/IP socket
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

    # Bind the socket to the address and port
    server_socket.bind((HOST, PORT))

    # Enable the server to accept connections (max 1 connection in this case)
    server_socket.listen(1)
    print(f"Server listening on {HOST}:{PORT}...")

    while True:
        # Accept a connection
        client_socket, client_address = server_socket.accept()
        print(f"Connection from {client_address}")

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
