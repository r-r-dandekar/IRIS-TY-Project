import subprocess
import time

def chat_with_llm():
    print("Chat with LLM (type 'exit' to stop):")
    
    while True:
        user_input = input("\nYou: ")
        if user_input.lower() == "exit":
            print("Exiting chat...")
            break

        print("\nLLM is thinking... ⏳")  # Indicate processing

        try:
            # Run Ollama and get response with UTF-8 decoding
            response = subprocess.run(
                ["ollama", "run", "mistral", user_input], 
                capture_output=True, 
                text=True, 
                encoding="utf-8", 
                check=True  # Will raise an exception if Ollama fails
            )
            
            # Extract and clean the output
            llm_output = response.stdout.strip()

            # Check if we got a valid output
            if llm_output:
                print(f"\nLLM: {llm_output}")
            else:
                print("\n⚠️ No response received! Make sure Ollama is running correctly.")

        except subprocess.CalledProcessError as e:
            print(f"\nError calling Ollama: {e.stderr}")
        except FileNotFoundError:
            print("\n❌ Ollama is not installed or not in the system path. Please install Ollama and ensure it's accessible.")
        except Exception as e:
            print(f"\n❌ Unexpected error: {e}")

# Run the chatbot
if _name_ == "_main_":
    chat_with_llm()