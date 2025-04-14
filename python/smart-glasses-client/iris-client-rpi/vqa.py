from transformers import ViltProcessor, ViltForQuestionAnswering
import requests
from PIL import Image
import cv2

processor = ViltProcessor.from_pretrained("dandelin/vilt-b32-finetuned-vqa")
model = ViltForQuestionAnswering.from_pretrained("dandelin/vilt-b32-finetuned-vqa")


def vqa(image, question):

	image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
	pil_image = Image.fromarray(image)

	# prepare inputs
	encoding = processor(pil_image, question, return_tensors="pt")

	# forward pass
	outputs = model(**encoding)
	logits = outputs.logits
	idx = logits.argmax(-1).item()
	
	return model.config.id2label[idx]
