#!/bin/bash
echo "Here I am: $0" > /home/radhan/test.txt
sleep 1
espeak-ng "Starting up IRIS client..."
echo "Testingggg" > /home/radhan/test.txt
source /home/radhan/iris-client-rpi/venv3.11.2/bin/activate
python /home/radhan/iris-client-rpi/client.py > /home/radhan/iris-client-rpi/client.log 2>&1
