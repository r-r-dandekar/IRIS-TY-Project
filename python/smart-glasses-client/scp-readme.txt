# Run all these on the Windows machine

# Copy from Pi to Windows
scp radhan@<ip>:/home/radhan/iris-client-rpi/<filename> "D:/Radhan-Data/Repos-And-Projects/IRIS-TY-Project/python/smart-glasses-client"
scp radhan@192.168.2.110:/home/radhan/iris-client-rpi/client.py "D:/Radhan-Data/Repos-And-Projects/IRIS-TY-Project/python/smart-glasses-client/iris-client-rpi"

# Copy from Windows to Pi
scp -r "D:/Radhan-Data/Repos-And-Projects/IRIS-TY-Project/python/smart-glasses-client/iris-client-rpi" radhan@<ip>:/home/radhan/
scp -r "D:/Radhan-Data/Repos-And-Projects/IRIS-TY-Project/python/smart-glasses-client/iris-client-rpi" radhan@192.168.2.110:/home/radhan/