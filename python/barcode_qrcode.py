import cv2
from pyzbar.pyzbar import decode
import requests

def detect_barcode_or_qr(image):

    if image is None:
        return {"message":"Something went wrong!"}

    # Convert to grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)

    # Detect barcodes and QR codes using pyzbar
    codes = decode(gray)

    if not codes:
        return None

    for code in codes:
        try:
            data = code.data.decode('utf-8')
            code_type = code.type

            if code_type == "QRCODE":
                print(f"QR Code detected: {data}")
                print("Redirecting to the site/payment gateway...")
                return {"qrcode":data}
            else:
                print(f"Barcode detected: {data}")
                print(f"Type: {code_type}")
                result = fetch_online_info(data)
                return {"barcode":data,"barcode_data":result}
        except Exception:
            return {"message":"Something went wrong!"}

def fetch_online_info(barcode_data):
    # Using Open Food Facts API
    api_url = f"https://world.openfoodfacts.org/api/v0/product/{barcode_data}.json"

    str=""

    try:
        response = requests.get(api_url)
        if response.status_code == 200:
            data = response.json()
            if data.get("status") == 1:  # Check if product is found
                product = data.get("product", {})

                # Product Name
                product_name = product.get("product_name", "No product name available.")
                str += f"Product Name: {product_name}"

                # Ingredients
                ingredients = product.get("ingredients_text", "No ingredient list found.")
                if ingredients != "No ingredient list found.":
                    ingredients_list = ingredients.split(",")
                    str += "Ingredients:"
                    for ingredient in ingredients_list:
                        str += f"  - {ingredient.strip()}"

                # Other available details
                categories = product.get("categories", "No categories available.")
                str += f"Categories: {categories}"

                brands = product.get("brands", "No brand information available.")
                str += f"Brand(s): {brands}"
            else:
                str += "Product not found in the database."
        else:
            print("Failed to fetch product information. API may be down.")
    except Exception as e:
        print(f"An error occurred while fetching product information: {e}")

    return str

if __name__=="__main__":
    # Example usage
    image_path = "badqr.jpg"
    print(detect_barcode_or_qr(cv2.imread(image_path)))
