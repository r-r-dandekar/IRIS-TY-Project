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

def combine_descriptions(descriptions, extra_instructions='none'):
  print("Original descriptions: "+str(descriptions))
  prompt=f"The following are descriptions about a scene. Some of them may be innaccurate. \
    Create an accurate description based on the common features of the various descriptions. \
    The new description should be in the same style as the original descriptions.\
    extra instructions: {extra_instructions}\
    descriptions: {descriptions}"
  combined = chat_session.send_message(prompt).text
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
  cleaned = chat_session.send_message(prompt).text
  print("Cleaned OCR Output: "+cleaned)
  return cleaned

if __name__=="__main__":
  while True:
    print('Enter your prompt:')
    prompt = input()
    response = chat_session.send_message(prompt)

    print(response.text)