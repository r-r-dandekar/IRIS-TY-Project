package com.example.cameratesting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameratesting.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import java.net.Socket
import java.io.File
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var previewView: PreviewView

    private lateinit var textToSpeech: TextToSpeech

    private lateinit var serverIPAddress : String
    private var serverPortNumber = 0

    private var photosPerCapture = 1

    // channel of base64 encoded images
    private val imageChannel = Channel<String>()

    private var name = "App"

    private var currentSocket: Socket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        previewView = findViewById(R.id.viewFinder)

        loadPreferences()

        // Request permissions
        if (allPermissionsGranted()) {
            startCamera()
            initSpeechRecognition()
            initTextToSpeech()
        } else {
            requestPermissions()
        }


        startSendingTCPThread()
        startReceivingTCPThread()

        // Set up the listeners for take photo and video capture buttons
        viewBinding.stopSpeakingButton.setOnClickListener { speak(" ") }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
    }

    private fun loadPreferences() {
        val sharedPreferences = getSharedPreferences("app_settings", MODE_PRIVATE)
        serverIPAddress = sharedPreferences.getString("server_ip_address", getString(R.string.server_default_ip_address)) ?: getString(R.string.server_default_ip_address)
        serverPortNumber = (sharedPreferences.getString("server_port_number", getString(R.string.server_default_port_number)) ?: getString(R.string.server_default_port_number)).toInt()
        photosPerCapture = (sharedPreferences.getString("photos_per_capture", getString(R.string.default_photos_per_capture)) ?: getString(R.string.default_photos_per_capture)).toInt()
        println("Server IP Address: $serverIPAddress")
        println("Server Port Number: $serverPortNumber")
        println("Photos Per Capture: $photosPerCapture")
    }

    private fun connectToServer(ipAddress: String, port: Int): Socket? {
        return try {
            // Establish TCP connection
            val socket = Socket(ipAddress, port)
            println("Connected to server: $ipAddress:$port")
            socket
        } catch (e: Exception) {
            println("Error connecting to server: $e")
            null
        }
    }

    private fun startSendingTCPThread() {
        // Use coroutine scope to run the monitoring logic
        GlobalScope.launch {
            while (true) {
                if (currentSocket == null || serverIPAddress != currentSocket?.inetAddress?.hostAddress || serverPortNumber != currentSocket?.port) {
                    // Close the current socket if it's open
                    currentSocket?.close()

                    // Connect to the new server details
                    currentSocket = connectToServer(serverIPAddress, serverPortNumber)
                }
                if (currentSocket != null) {
                    val socket = currentSocket ?: continue
                    val outputStream = socket.getOutputStream()
                    for (base64Image in imageChannel) {
                        outputStream.write(base64Image.toByteArray())
                        outputStream.flush()
                    }
                }
                delay(50) // Wait before checking again
            }
        }
    }

    private fun startReceivingTCPThread() {
        // Use coroutine scope to run the monitoring logic
        GlobalScope.launch {
            while (true) {
                if (currentSocket != null) {
                    val socket = currentSocket ?: continue
                    val inputStream: InputStream = socket.getInputStream()
                    if (inputStream.available() > 0) {
                        // Read the data from the server
                        val buffer = ByteArray(inputStream.available())  // create a buffer to store the incoming data
                        inputStream.read(buffer)

                        // Convert the received bytes to a string (or process as needed)
                        val receivedData = String(buffer)
                        val speechString = formatJsonString(receivedData)
                        println("Received data: $receivedData")
                        speak(speechString)
                    }
                }
                delay(50) // Wait before checking again
            }
        }
    }

    private fun formatJsonString(jsonString: String): String {
        // Parse the JSON string into a JSONObject
        val jsonObject = JSONObject(jsonString)

        // Initialize a list to hold the parts of the result string
        var result : String = ""

        if (jsonObject.has("image_caption")) {
            val value = jsonObject.getString("image_caption")
            result = "I see $value"
        } else if (jsonObject.has("ocr_text")) {
            val value = jsonObject.getString("ocr_text")
            result = value
        } else if (jsonObject.has("found_face")) {
            println("FACEEEE"+jsonObject.getString("found_face"))
            result = jsonObject.getString("face_name")
        }

        // Join the list into a single string with " and " separator
        return result
    }

    private fun changeVoice() {
        val voices = textToSpeech.voices

        println("Available voices:")
        for (voice in voices)
            println("\t$voice")

        while (voices.isNotEmpty()) {
            // Pick a random voice from the list
            val randomVoice = voices.random()  // Randomly select a voice

            if (randomVoice != textToSpeech.voice) {
                textToSpeech.voice = randomVoice
                break
            }
        }

        speak("Hello")
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(
            applicationContext
        ) { i ->
            // if No error is found then only it will run
            if (i != TextToSpeech.ERROR) {
                // To Choose language of speech
                textToSpeech.setLanguage(Locale.UK)
            }
        }
    }

    private fun produceImages(savedPaths: MutableList<String>, extraInstructions:String="None",
                              command : String, resolutionFactor : Int = 400) {
        val base64Images : MutableList<String> = mutableListOf()
        for (savedPath in savedPaths) {
            val file = File(savedPath)
            println("SAVED AT $savedPath")
            if (!file.exists()) {
                println("File not found at $savedPath")
                return
            }

            try {
                val bitmap = BitmapFactory.decodeFile(savedPath)

                val original_width = bitmap.width
                val original_height = bitmap.height

                var ratio = 0f

                if (original_height != 0) {
                    ratio = original_width.toFloat() / original_height.toFloat()
                }
                val width = (resolutionFactor*ratio).toInt()
                val height = (resolutionFactor).toInt()
                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)

                val byteArrayOutputStream = ByteArrayOutputStream()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
                val byteArray = byteArrayOutputStream.toByteArray()

                // Encode the byte array to Base64
                val base64Image = Base64.encodeToString(byteArray, Base64.DEFAULT)
                base64Images.add(base64Image)

            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        var json : String = "{\"command\":\"$command\",\"images\":["
        for (base64Image in base64Images) {
//            json +="\"$base64Image\","
            json +="\"$base64Image\","
        }
        json = json.dropLast(1)+"], \"extra_instructions\":\"$extraInstructions\"}"
        imageChannel.trySend(json)
    }

    private fun multiImageCommand(imagePaths: MutableList<String> = mutableListOf(), count: Int = photosPerCapture,
                              extraInstructions: String = "None", command : String, resolutionFactor: Int = 400) {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val tempFile = File(this.cacheDir, "$name.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : OnImageSavedCallback {

            // This method is invoked when the image is saved successfully
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // Path of the saved image in the temporary directory
                val savedPath = tempFile.absolutePath
                println("Image saved at: $savedPath")
                imagePaths.add(savedPath)
                if (count <= 1) {
                    produceImages(imagePaths, extraInstructions=extraInstructions,
                                command=command, resolutionFactor)
                }
                else {
                    Thread.sleep(150)
                    multiImageCommand(imagePaths, count-1, extraInstructions=extraInstructions,
                        command=command, resolutionFactor)
                }
            }

            // This method is invoked if there is an error during image capture
            override fun onError(exception: ImageCaptureException) {
                // Handle errors in image capture
                exception.printStackTrace()
            }
        })
    }

    private fun describeScene(imagePaths: MutableList<String> = mutableListOf(), count: Int = photosPerCapture,
                              extraInstructions: String = "None") {
        multiImageCommand(imagePaths=imagePaths, count=count,
            extraInstructions=extraInstructions, command="image_caption")
    }

    private fun ocr(imagePaths: MutableList<String> = mutableListOf(), count: Int = photosPerCapture,
                              extraInstructions: String = "None") {
        multiImageCommand(imagePaths=imagePaths, count=count,
            extraInstructions=extraInstructions, command="ocr", resolutionFactor=700)
    }

    private fun face_recognition(imagePaths: MutableList<String> = mutableListOf(), count: Int = photosPerCapture,
                    extraInstructions: String = "None") {
        multiImageCommand(imagePaths=imagePaths, count=count,
            extraInstructions=extraInstructions, command="face_recognition", resolutionFactor=700)
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val tempFile = File(this.cacheDir, "$name.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : OnImageSavedCallback {

            // This method is invoked when the image is saved successfully
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // Path of the saved image in the temporary directory
                val savedPath = tempFile.absolutePath
                println("Image saved at: $savedPath")
            }

            // This method is invoked if there is an error during image capture
            override fun onError(exception: ImageCaptureException) {
                // Handle errors in image capture
                exception.printStackTrace()
            }
        })
    }

    fun openSettings(view: View) {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun speak(text : String) {
        textToSpeech.speak(text,TextToSpeech.QUEUE_FLUSH,null);
    }

    private fun lookingMessage() {
        val messages = listOf(
            "Hmm",
            "Let's see...",
            "Hold on...",
            "Give me a moment...",
            "One moment...",
            "Just a second...",
            "Wait a minute...",
            "Hold on a second...",
            "Just a moment...",
            "I’m looking into it..."
        )

        val randomMessage = messages[Random.nextInt(messages.size)]

        speak(randomMessage)
    }

    private fun speechDetected(data : ArrayList<String>) {
        var str = data.joinToString(" ")
        if (str.contains(name, ignoreCase = true)) {
            str = str.lowercase().substringAfter(name.lowercase()+" ")
            println(str)
            if (str.contains("what do you see")) {
                val extraInstructions = str.substringAfter("what do you see").trim()
                describeScene(extraInstructions=extraInstructions)
                lookingMessage()
            }
            if (str.contains("tell me what you see")) {
                val extraInstructions = str.substringAfter("tell me what you see").trim()
                describeScene(extraInstructions=extraInstructions)
                lookingMessage()
            }
            if (str.contains("ocr")) {
                val extraInstructions = str.substringAfter("ocr").trim()
                ocr(extraInstructions=extraInstructions)
                lookingMessage()
            }
            if (str.contains("read this")) {
                val extraInstructions = str.substringAfter("read this").trim()
                ocr(extraInstructions=extraInstructions)
                lookingMessage()
            }
            if (str.contains("what is written")) {
                val extraInstructions = str.substringAfter("what is written").trim()
                ocr(extraInstructions=extraInstructions)
                lookingMessage()
            }
            if (str.contains("who is this")) {
                val extraInstructions = str.substringAfter("who is this").trim()
                face_recognition(extraInstructions=extraInstructions)
                lookingMessage()
            }
            if (str.contains("count the objects")) {
                // implement this
            }
            else if (str.equals("change your voice") ||
                str.equals("change voice"))
                changeVoice()
            else if (str.equals("hello"))
                speak("Hi")
            else if (str.equals("hi"))
                speak("Hello")
            else if (str.contains("echo"))
                speak(str.substringAfter("echo"))
            else if (str.contains("say"))
                speak(str.substringAfter("say"))
            else if (str.contains("change your name to ")) {
                name = str.substringAfter("change your name to").trim()
                speak("My new name is $name")
            }
        }
    }

    private fun initSpeechRecognition() {
        // Get SpeechRecognizer instance
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Speech recognition service NOT available
            return
        }
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: Bundle?) {}

            override fun onBeginningOfSpeech() {
                Log.i("SpeechRecognizer", "Beginning of speech")
            }

            override fun onRmsChanged(p0: Float) {}

            override fun onBufferReceived(p0: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(p0: Int) {
                speechRecognizer.startListening(recognizerIntent)
            }

            override fun onResults(results: Bundle)  {
                val data: ArrayList<String>? = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("SpeechRecognizer", "Speech recognition results received: $data")
                if (data != null) {
                    speechDetected(data)
                }
                speechRecognizer.startListening(recognizerIntent)
            }

            override fun onPartialResults(p0: Bundle?) {}

            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
        speechRecognizer.startListening(recognizerIntent)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {

                    it.surfaceProvider = previewView.surfaceProvider
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA


            // Set up the ImageCapture use case
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // Choose CAPTURE_MODE_MAXIMIZE_QUALITY if needed
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    private fun requestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            // Request missing permissions
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                100
            )
        }
        println("Hi")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

}
