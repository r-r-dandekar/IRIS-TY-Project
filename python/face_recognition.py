from deepface import DeepFace
import cv2
import os

def find_face(image):
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
            return face_name
        else:
            return None
    except Exception:
        return None

if __name__=="__main__":
    image = cv2.imread("face.jpg")
    print(find_face(image))