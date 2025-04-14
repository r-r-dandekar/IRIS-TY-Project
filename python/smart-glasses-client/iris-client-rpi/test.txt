# For installing the stuff
python -m venv --system-site-packages venv3.11.2
source venv3.11.2/bin/activate
pip install --upgrade numpy
pip install ultralytics
pip install --force-reinstall simplejpeg
python object_detection.py

# For installing speech recognition you need to do these
sudo apt-get install portaudio19-dev python3-pyaudio
sudo apt-get install flac

# For text to speech
sudo apt-get install espeak espeak-ng

