from dotenv import load_dotenv
from os import environ
import google.generativeai as genai

load_dotenv()
gemini_api_key = environ.get('GEMINI_API_KEY')
genai.configure(api_key=gemini_api_key)

# Create the model
generation_config = {
  "temperature": 1,
  "top_p": 0.95,
  "top_k": 64,
  "max_output_tokens": 8192,
  "response_mime_type": "text/plain",
}

model = genai.GenerativeModel(
  model_name="gemini-1.5-flash",
  generation_config=generation_config,
)

chat_session = model.start_chat(
  history=[
  ]
)

def summarize_barcode_data(data, extra_instructions='none'):
  print("Original barcode data: "+str(data))
  prompt=f"The following is detailed information about a product. Generate a short summary of this information,\
    trying to include only that information which is needed for a layman, unless any extra instructions are given.\
    If any extra instructions are given, follow those instructions.\
    extra instructions: {extra_instructions}\
    information: {data}"
  combined = llm_response(prompt)
  print("Summarized barcode data: "+combined)
  return combined

def combine_descriptions(descriptions, extra_instructions='none'):
  print("Original descriptions: "+str(descriptions))
  prompt=f"The following are possibilities about a scene. Some of them may be innaccurate. \
    Create an accurate description based on the common features of the various possibilities. \
    The new description should be in the same style as the original possibilities.\
    As the possibilities may be inaccurate, rephrase the information in a less specific way.\
    If any information is present in some, but not in the majority, of possibilities,\
    try to omit that information. \
    extra instructions: {extra_instructions}\
    possibilities: {descriptions}"
  combined = llm_response(prompt)
  print("Compbined description: "+combined)
  return combined

def clean_ocr_output(outputs, extra_instructions='none'):
  outputs_text = ''
  for i, output in enumerate(outputs):
    outputs_text += f"** PASSAGE {i} **{output}\n\n\n"
  print(outputs_text)
  prompt=f"The following passages are OCR outputs of the same text. Due to slight variations in \
    the photos, the outputs are slightly different, but they are photos of the same text. \
    All of the passages are slightly innaccurate. Some of the passages may contain complete \
    gibberish or be unintelligible. Ignore unintelligible passages.\
    Your task is to analyze the readable passages and \
    provide an accurate version based on the common parts of these passages. The final output \
    should be an accurate, complete, and coherent passage that reflects the original text.\
    Do not use asterisks (*) for emphasis, unless they are present in the original text.\
    If you cannot decipher the results, simply say that the text is not clear or that \
    you see no text, and keep your response short if you cannot decipher the results.\
    Do not mention the passages by name. Do not say 'provided text', instead use \
    phrases like 'I see'.\
    If you are not able to clearly read the whole text, focus on what you can see, and \
    keep the rest of the response short. Do not give a summary of the passage, \
    instead read whatever is legible, make guesses if needed. \
    extra instructions: {extra_instructions}\
    passages: {outputs_text}"
  cleaned = llm_response(prompt)
  print("Cleaned OCR Output: "+cleaned)
  return cleaned

def llm_response(prompt):
  return chat_session.send_message(prompt).text

if __name__=="__main__":
  while True:
    print('Enter your prompt:')
    prompt = input()
    response = llm_response(prompt)

    print(response)