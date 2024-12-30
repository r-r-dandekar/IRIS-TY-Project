import socket
import cv2
import numpy as np
import base64

# Create a UDP socket
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

# Bind the socket to address and port
server_address = ('', 55555)
sock.bind(server_address)

print(f"Listening for UDP packets on {server_address}")

while True:
    try:
        # Receive data from the socket (buffer size of 65535 bytes, which is the max size for a UDP packet)
        #size_data, address = sock.recvfrom(65535)
        #print(size_data)
        #size = struct.unpack("!I", size_data)[0]
        
        # Print the received data
        #print(f"Received {len(size_data)} bytes from {address}")
        #print(f"Data: {size_data}")

        #frame_data, addr = sock.recvfrom(size)
        frame_data, addr = sock.recvfrom(65535)
        #frame = np.frombuffer(frame_data, dtype=np.uint8)
        
        
        padding = len(frame_data) % 4
        if padding != 0:
            frame_data += b'=' * (4 - padding)
        
        # Decode the base64 string
        image_data = base64.b64decode(frame_data)

        # Convert the decoded byte data to a numpy array
        nparr = np.frombuffer(image_data, np.uint8)

        # Decode the numpy array into an image
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        # Display the image using OpenCV
        cv2.imshow('Decoded Image', img)
        cv2.waitKey(0)
        cv2.destroyAllWindows()

    except Exception as e:
        print(f"Error receiving data: {e}")
