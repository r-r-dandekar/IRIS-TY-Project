import cv2
from picamera2 import Picamera2

from ultralytics import YOLO

# Initialize the Picamera2
picam2 = Picamera2()
picam2.preview_configuration.main.size = (1280, 720)
picam2.preview_configuration.main.format = "RGB888"
picam2.preview_configuration.align()
picam2.configure("preview")
picam2.start()

# Load the YOLO11 model
model = YOLO("/home/radhan/iris-client-rpi/yolo_model.pt")

while True:
    # Capture frame-by-frame
    frame = picam2.capture_array()

    # From test.py
    image = frame
            
    # Get original dimensions
    h, w = image.shape[:2]

    # Determine scaling factor
    scale = 640 / max(w, h)  # Scale so the largest dimension is 640
    new_w, new_h = int(w * scale), int(h * scale)

    # Resize image
    resized_image = cv2.resize(image, (new_w, new_h), interpolation=cv2.INTER_LINEAR)

    # Perform object detection
    results = model(resized_image, conf=0.1)
    
    annotated_image = results[0].plot()
    cv2.imshow("YOLO Output", annotated_image)
    cv2.waitKey(10)
    
    #   ORIGINAL
    # Run inference on the frame
    #results = model(frame, conf=0.3)

    # Visualize the results on the frame
    #annotated_frame = results[0].plot()
