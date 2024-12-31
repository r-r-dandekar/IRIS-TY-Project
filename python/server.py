from nlp_utils import combine_descriptions, clean_ocr_output
import socket
import cv2
import numpy as np
import base64
from object_detection import show_results_from_opencv_image
import json
from image_captioning import predict_step_from_rgb_images
import time
from sys import stdout
import re
from ocr_utils import image_to_string


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
            i = 0
            while i < 4:
                text = image_to_string(image)
                i+=1
                ocr_output.append(text)
                image = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)

        text = clean_ocr_output(ocr_output, extra_instructions=extra_instructions)
        results={"ocr_text":text}
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

def run_command(json):
    print("command: "+json["command"])
    if json["command"]=="image_caption":
        image_caption(json)
    elif json["command"]=="ocr":
        ocr(json)

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
