from vectordb import VectorDatabase
from deepface import DeepFace
import string
import cv2
import os
import random

db = VectorDatabase()


def find_face_old(image):
    filename = "{}.jpg".format(os.getpid())
    cv2.imwrite(filename, image)
    try:
        dfs = DeepFace.find(
            img_path = filename,
            db_path = "face_db",
        )
        os.remove(filename)
        if dfs:
            frame = dfs[0]
            first_match = frame['identity'].iloc[0]
            face_name = os.path.splitext(os.path.basename(first_match))[0]
            return face_name.split('_')[0]
        else:
            return None
    except Exception:
        return None
    

def find_face(image):
    filename = "{}.jpg".format(os.getpid())
    cv2.imwrite(filename, image)
    try:
        embedding = DeepFace.represent(
            img_path = filename,
            enforce_detection = False,
        )[0]['embedding']
        os.remove(filename)
        results = db.search(embedding)
        print(results)
        if results:
            name, confidence = results[0]
            confidence_threshold = 0.4
            if confidence > confidence_threshold:
                return name
            else:
                return None
    except Exception as e:
        print(f"Error in find_face(): {e}")
        return None

def save_images_with_unique_names(images, name):
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

        return saved_file_names
    
def add_faces(images, name):
    img_paths = save_images_with_unique_names(images,name)
    # with open('face_db/document.csv','a') as fd:
    for img_path in img_paths:
        embedding_objs = DeepFace.represent(
            img_path = 'face_db/'+img_path,
            enforce_detection = False,
        )
        db.save_embedding(embedding_objs[0]['embedding'], name)
        # fd.write(str(embedding_objs[0])+","+name)


if __name__=="__main__":
    image = cv2.imread("face.jpg")
    add_faces([image], 'pradnesh')
    image = cv2.imread("test.jpg")
    print(find_face(image))