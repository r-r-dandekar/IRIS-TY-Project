import signal
import time

class TimeoutException(Exception):   # Custom exception class
    pass

def timeout_handler(signum, frame):   # Custom signal handler
    raise TimeoutException

def my_function():
    # Simulate a long-running task
    print("Function started")
    time.sleep(5)  # This task takes 5 seconds
    print("Function completed")
    return "Test"

# Change the behavior of SIGALRM
signal.signal(signal.SIGALRM, timeout_handler)

for i in range(3):
    # Start the timer. Once 5 seconds are over, a SIGALRM signal is sent.
    signal.alarm(5)    
    # This try/except loop ensures that 
    #   you'll catch TimeoutException when it's sent.
    try:
        A(i) # Whatever your function that might hang
    except TimeoutException:
        continue # continue the for loop if function A takes more than 5 second
    else:
        # Reset the alarm
        signal.alarm(0)