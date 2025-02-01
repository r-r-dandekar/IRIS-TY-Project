# Ref: https://lopezyse.medium.com/computer-vision-object-detection-with-python-14b241f97fd8

import cv2
import numpy as np
from ultralytics import YOLO
import matplotlib.pyplot as plt
from sys import stdout

default_confidence_threshold = 0.3

# Load YOLO model
model = YOLO('yolov8n.pt')  # Load the model

def detect_objects(image_rgb):
    """
    Detect objects in an image using YOLOv8.
    
    Args:
        image_rgb: an OpenCV image in RGB format
    
    Returns:
        Detected objects and class labels.
    """
    
    # Perform detection
    results = model(image_rgb)[0]
    
    # Create a copy of the image for drawing
    annotated_image = image_rgb.copy()
    
    # Generate random colors for classes
    np.random.seed(42)  # For consistent colors
    colors = np.random.randint(0, 255, size=(100, 3), dtype=np.uint8)
    
    # Process detections
    boxes = results.boxes

    return boxes, results.names, annotated_image, colors

def show_results_from_opencv_image(image, confidence_threshold=default_confidence_threshold):
    """
    Show original image and detection results side by side.

    Args:
        image: an OpenCV image in RGB format
        confidence_threshold: Minimum confidence score for detections
    """
    
    # Get detection results
    boxes, class_names, annotated_image, colors = detect_objects(image)
    stdout.flush()   

    detected = {}
    
    # Process each detected object and apply confidence threshold filtering
    for box in boxes:
                
        # Get confidence score
        confidence = float(box.conf[0])
        
        # Only show detections above confidence threshold
        if confidence > confidence_threshold:
            # Get class id and name
            class_id = int(box.cls[0])
            class_name = class_names[class_id]
            
            # Add 1 to detected[class_name], with default value 0
            detected[class_name] = detected.get(class_name, 0) + 1
            
    return detected


def show_results(image_path, confidence_threshold=default_confidence_threshold):
    """
    Show original image and detection results side by side.

    Args:
        image_path: Path to the input image
        confidence_threshold: Minimum confidence score for detections
    """
    original_image = cv2.imread(image_path)
    original_image = cv2.cvtColor(original_image, cv2.COLOR_BGR2RGB)
    return show_results_from_opencv_image(original_image, confidence_threshold)

if __name__=="__main__":
    # Example usage:
    print(show_results('test.jpg', confidence_threshold=0.2))