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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random
import android.media.AudioManager
import androidx.camera.core.ImageAnalysis
import com.example.cameratesting.ml.SsdMobilenetV11Metadata1
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.net.InetSocketAddress


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private lateinit var labels: List<String>
    private var mostDominantObject: String? = null
    private var mostDominantObjectScore: Float = 0f
    private val paint = Paint()
    private val objectDetectionColors = listOf<Int>(Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED)
//    private lateinit var imageView: ImageView

    private lateinit var model: SsdMobilenetV11Metadata1

    private lateinit var imageProcessor: ImageProcessor

    val scanner = BarcodeScanning.getClient()

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

    private var name = "IRIS"

    private var currentSocket: Socket? = null

    private var heartbeatCounter: Int = 0
    private val heartbeatCounterLimit: Int = 50

    private val connectTimeoutMillis = 3000

    private var barcodeCounter = 0
    private var barcodeExtraInstructions = ""

    private var objectDetectionCounter = 0
    private var objectDetectionExtraInstructions = ""
    private var objectSpeakCounter = 0

    val objectDetectionOptions = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableClassification()  // Optional
        .build()
    val objectDetector = ObjectDetection.getClient(objectDetectionOptions)

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

        val audio = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, AudioManager.FLAG_SHOW_UI)

        startSendingTCPThread()
        startReceivingTCPThread()
        startHeartbeatThread()
        startBarcodeListener()
        startObjectDetectionListener()

        // Set up the listeners for take photo and video capture buttons
        viewBinding.stopSpeakingButton.setOnClickListener { speak(" ") }

        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)

//        imageView = findViewById(R.id.viewFinder)

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
            val socket = Socket()
            socket.connect(InetSocketAddress(ipAddress, port), connectTimeoutMillis)
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

    private fun startHeartbeatThread() {
        // This thread sends heartbeats, increments hearbeatCounter, and
        // checks if the counter has exceeded its maximum limit
        // This thread also handles connection to the server
        // The receiving TCP thread hangles receiving heartbeat acks and
        // resetting the counter
        GlobalScope.launch {
            var i = 0
            while (true) {
                if (currentSocket == null || serverIPAddress != currentSocket?.inetAddress?.hostAddress || serverPortNumber != currentSocket?.port) {
                    // Close the current socket if it's open
                    currentSocket?.close()

                    // Connect to the new server details
                    println("Trying to connect...")
                    try {
                        currentSocket = connectToServer(serverIPAddress, serverPortNumber)
                    } catch (e: Exception) {
                        println("Couldn't connect to server: $e")
                    }
                }
                if (i==0 && currentSocket != null) {
                    val socket = currentSocket ?: continue
                    val json_heartbeat="{\"command\":\"heartbeat\"}"
                    imageChannel.trySend(json_heartbeat)
                    synchronized (this) {
                        heartbeatCounter++
                        if (heartbeatCounter > heartbeatCounterLimit) {
                            println("No response to heartbeats: Closing socket")
                            socket.close()
                            currentSocket = null
                        }
                    }
                }
                delay(500) // Wait before checking again
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
                        processReceivedMessage(receivedData)
                    }
                }
                delay(50) // Wait before checking again
            }
        }
    }


    private fun startBarcodeListener() {
        GlobalScope.launch {
            while (true) {
                if (barcodeCounter > 0) {
                    barcode_qrcode(extraInstructions = barcodeExtraInstructions)
                    delay(50) // Wait before checking again
                }
                else {
                    delay(250)
                    barcodeCounter--
                }
            }
        }
    }

    private fun startObjectDetectionListener() {
        GlobalScope.launch {
            while (true) {
                if (objectDetectionCounter > 0)
                    objectDetectionCounter--
                if (objectSpeakCounter > 0)
                    objectSpeakCounter--
                delay(50)
//                if (objectDetectionCounter > 0) {
//                    objectDetection(extraInstructions = objectDetectionExtraInstructions)
//                    delay(500) // Wait before checking again
//                }
//                else {
//                    delay(1500)
//                }
            }
        }
    }

    private fun processReceivedMessage(jsonString: String) {
        // Parse the JSON string into a JSONObject
        val jsonObject = JSONObject(jsonString)

        // Initialize a list to hold the parts of the result string
        var result : String = ""

        if (jsonObject.has("image_caption")) {
            val value = jsonObject.getString("image_caption")
            result = "I see $value"
            speak(result)
        }
        if (jsonObject.has("objects")) {
            val objects = jsonObject.getJSONObject("objects")

            // Build the sentence
            val list = mutableListOf<String>()
            for (key in objects.keys()) {
                val value = objects.getInt(key)
                list.add("$value $key")
            }
            result = "I see " + list.joinToString(" and ")
            speak(result)
        }
        if (jsonObject.has("ocr_text")) {
            val value = jsonObject.getString("ocr_text")
            result = value
            speak(result)
        }
        if (jsonObject.has("found_face")) {
            if (jsonObject.getString("found_face") == "false")
                result = "I can't recognize this person"
            else
                result = "This is "+    jsonObject.getString("face_name")
            speak(result)
        }
        if (jsonObject.has("qrcode")) {
            result = "QR Code: "+jsonObject.getString("qrcode")
            speak(result)
        }
        if (jsonObject.has("barcode")) {
            result = "Barcode Data: "+jsonObject.getString("barcode_data")
            speak(result)
        }
        if (jsonObject.has("message")) {
            result = jsonObject.getString("message")
            speak(result)
        }
        if (jsonObject.has("heartbeat")) {
            val json_response="{\"command\":\"heartbeat_ack\"}"
            imageChannel.trySend(json_response)
        }
        if (jsonObject.has("heartbeat_ack")) {
            synchronized(this) {
                heartbeatCounter = 0
            }
        }
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
                              command : String, resolutionFactor : Int = 100) {
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
                    Thread.sleep(10)
                    println("HELLOO "+count)
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

    private fun add_face(imagePaths : MutableList<String> = mutableListOf(), name: String) {
        multiImageCommand(imagePaths=imagePaths, count=1, extraInstructions=name, command = "add_face", resolutionFactor=1000)
    }
    private fun face_recognition(imagePaths: MutableList<String> = mutableListOf(), count: Int = photosPerCapture,
                                 extraInstructions: String = "None") {
        multiImageCommand(imagePaths=imagePaths, count=count,
            extraInstructions=extraInstructions, command="face_recognition", resolutionFactor=1000)
    }
    private fun count_objects(imagePaths: MutableList<String> = mutableListOf(), count: Int = photosPerCapture,
                              extraInstructions: String = "None") {
        multiImageCommand(imagePaths=imagePaths, count=count,
            extraInstructions=extraInstructions, command="count_objects")
    }
    private fun barcode_qrcode(extraInstructions : String = "") {
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
                val bitmap = BitmapFactory.decodeFile(savedPath)
                val inputImage = InputImage.fromBitmap(bitmap, 0)
//                var gotBarcode = false
                val result = scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
//                        gotBarcode = true
                        for (barcode in barcodes) {
                            barcodeCounter = 0
                            val bounds = barcode.boundingBox
                            val corners = barcode.cornerPoints

                            val rawValue = barcode.rawValue

                            val valueType = barcode.valueType
                            // See API reference for complete list of supported types
                            when (valueType) {
                                Barcode.TYPE_PRODUCT -> {
                                    speak("Found a product barcode. Checking for product information...")
                                    val json ="{\"command\":\"barcode\",\"barcode_raw_value\":\"$rawValue\",\"extra_instructions\":\"$extraInstructions\"}"
                                    imageChannel.trySend(json)
                                }
                                Barcode.TYPE_URL -> {
                                    speak("URL barcode: $rawValue")
                                }
                            }
                        }
                    }
                    .addOnFailureListener {
                    }
//                if (!gotBarcode)
//                    speak("no barcode found")
            }

            // This method is invoked if there is an error during image capture
            override fun onError(exception: ImageCaptureException) {
                // Handle errors in image capture
                exception.printStackTrace()
            }
        })
    }


    private fun objectDetection(extraInstructions : String = "") {
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
                val bitmap = BitmapFactory.decodeFile(savedPath)
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                println("HELOEHIUYHBINJU")
                val result = objectDetector.process(inputImage)
                    .addOnSuccessListener { detectedObjects ->
                        for (detectedObject in detectedObjects) {
                            println(detectedObject)
                            val boundingBox = detectedObject.boundingBox
                            val trackingId = detectedObject.trackingId
                            for (label in detectedObject.labels) {
                                val text = label.text
                                println(text)
                                speak(text)
//                                if (PredefinedCategory.FOOD == text) {
//                                    speak("Food: $text")
//                                }
//                                val index = label.index
//                                if (PredefinedCategory.FOOD_INDEX == index) {
//                                    speak("Food Index: $text")
//                                }
                                val confidence = label.confidence
                            }
                        }
                    }
                    .addOnFailureListener {
                    }
//                if (!gotBarcode)
//                    speak("no barcode found")
            }

            // This method is invoked if there is an error during image capture
            override fun onError(exception: ImageCaptureException) {
                // Handle errors in image capture
                exception.printStackTrace()
            }
        })
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
//        var str = data.joinToString(" ")
        var match = false
        for (possibility in data) {
            if (match)      // if a command has already been identified for one of the
                break       // possible sentences, don't look at the other possibilities
            var str = possibility

            // Detect name, or hardcoded aliases for 'IRIS'
            if (str.contains(name, ignoreCase = true) || str.contains("Irish", ignoreCase = true)
                || str.contains("I guess", ignoreCase = true)
                || str.contains("I reached", ignoreCase = true)) {
                if (str.contains(name, ignoreCase = true))
                    str = str.lowercase().substringAfter(name.lowercase()+" ")
                else if (str.contains("Irish", ignoreCase = true))
                    str = str.lowercase().substringAfter("irish ")
                else if (str.contains("I guess", ignoreCase = true))
                    str = str.lowercase().substringAfter("i guess ")
                else if (str.contains("I reached", ignoreCase = true))
                    str = str.lowercase().substringAfter("i reached ")
                println(str)
                if (str.contains("what do you see")) {
                    val extraInstructions = str.substringAfter("what do you see").trim()
                    describeScene(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                if (str.contains("tell me what you see")) {
                    val extraInstructions = str.substringAfter("tell me what you see").trim()
                    describeScene(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                if (str.contains("what is this")) {
                    val extraInstructions = str.substringAfter("what is this").trim()
                    describeScene(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                if (str.contains("ocr")) {
                    val extraInstructions = str.substringAfter("ocr").trim()
                    ocr(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                if (str.contains("read this")) {
                    val extraInstructions = str.substringAfter("read this").trim()
                    ocr(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                if (str.contains("what is written")) {
                    val extraInstructions = str.substringAfter("what is written").trim()
                    ocr(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                if (str.contains("who is this")) {
                    val extraInstructions = str.substringAfter("who is this").trim()
                    face_recognition(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                if(str.contains("this is")){
                    val name = str.substringAfter("this is").trim()
                    add_face(name = name)
                    lookingMessage()
                    match = true
                }
                if (str.contains("count the object")) {
                    val extraInstructions = str.substringAfter("count the object").trim()
                    count_objects(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
//                if (str.contains("count object")) {
//                    val extraInstructions = str.substringAfter("count object").trim()
//                    count_objects(extraInstructions=extraInstructions)
//                    lookingMessage()
//                    match = true
//                }
//                if (str.contains("what objects")) {
//                    val extraInstructions = str.substringAfter("what objects").trim()
//                    count_objects(extraInstructions=extraInstructions)
//                    lookingMessage()
//                    match = true
//                }
                if (str.contains("scan")) {
                    val regex = Regex("(barcode|bar code|qr code)", RegexOption.IGNORE_CASE)
                    // Replace all matches with an empty string
                    barcodeExtraInstructions = regex.replace(str.substringAfter("scan").trim(), "").trim()
                    barcodeCounter=50000

                    speak("Looking for barcodes")
                    match = true
                }
                if (str.contains("object")) {
                    if (str.contains("stop")) {
                        objectDetectionCounter=0
                        objectSpeakCounter=0
                        speak("No longer looking for objects")
                    }
                    else {
                        objectDetectionExtraInstructions = str.substringAfter("object").trim()
                        objectDetectionCounter=1000
                        objectSpeakCounter=50
                        speak("Looking for objects")
                    }
                    match = true
                }
                if (str.equals("change your voice") ||
                    str.equals("change voice")) {
                    changeVoice()
                    match = true
                }
                if (str.equals("hello")) {
                    speak("Hi")
                    match = true
                }
                if (str.equals("hi")) {
                    speak("Hello")
                    match = true
                }
                if (str.contains("echo")) {
                    speak(str.substringAfter("echo"))
                    match = true
                }
                else if (str.contains("say")) {
                    speak(str.substringAfter("say"))
                    match = true
                }
                if (str.contains("change your name to ")) {
                    name = str.substringAfter("change your name to").trim()
                    speak("My new name is $name")
                    match = true
                }
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
            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                10
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
                startListening(speechRecognizer, recognizerIntent)
            }

            override fun onResults(results: Bundle)  {
                val data: ArrayList<String>? = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("SpeechRecognizer", "Speech recognition results received: $data")
                if (data != null) {
                    speechDetected(data)
                }
                startListening(speechRecognizer, recognizerIntent)
            }

            override fun onPartialResults(p0: Bundle?) {}

            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
        startListening(speechRecognizer, recognizerIntent)
    }

    private fun startListening(speechRecognizer: SpeechRecognizer, recognizerIntent: Intent) {
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

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, { imageProxy ->
                        runOnUiThread {
                            val bitmap = previewView.bitmap
                            if (bitmap != null) {
                                processFrameForObjectDetection(bitmap)
//                                val image = InputImage.fromBitmap(bitmap, 0)
//                                objectDetector.process(image)
//                                    .addOnSuccessListener { detectedObjects ->
//                                        objectsDetected(detectedObjects)
//                                    }
                            }
                        }
                        imageProxy.close()
                    })
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
                    this, cameraSelector, preview, imageCapture, imageAnalysis
                )

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

    private fun processFrameForObjectDetection(bitmap: Bitmap) {
        val image = TensorImage.fromBitmap(bitmap)
        val processedImage = imageProcessor.process(image)

        // Run object detection
        val outputs = model.process(processedImage)
        val locations = outputs.locationsAsTensorBuffer.floatArray
        val classes = outputs.classesAsTensorBuffer.floatArray
        val scores = outputs.scoresAsTensorBuffer.floatArray

        // Find the most dominant object
        var maxScore = 0f
        var dominantObject = ""
        var dominantIndex = -1
        scores.forEachIndexed { index, score ->
            if (score > maxScore) {
                maxScore = score
                dominantObject = labels[classes[index].toInt()]
                dominantIndex = index
            }
        }

        // If the most dominant object has changed, update it
        if (dominantObject != mostDominantObject) {
            mostDominantObject = dominantObject
            mostDominantObjectScore = maxScore
            println("$mostDominantObject : $maxScore")

            println("$objectDetectionCounter $objectSpeakCounter")
            if (objectDetectionCounter > 0 && objectSpeakCounter == 0 && maxScore > 0.5) {
                speak(mostDominantObject!!)
                objectSpeakCounter = 50
            }
//            if (!isSpeaking) {
//                speakDominantObject()
//            }
        }

        // Draw bounding boxes and labels on the bitmap
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val h = mutableBitmap.height
        val w = mutableBitmap.width
        paint.textSize = h / 15f
        paint.strokeWidth = h / 85f

        scores.forEachIndexed { index, score ->
            if (score > 0.5) {
                val x = index * 4
                paint.color = objectDetectionColors[index % objectDetectionColors.size]
                paint.style = Paint.Style.STROKE
                canvas.drawRect(
                    RectF(
                        locations[x + 1] * w,
                        locations[x] * h,
                        locations[x + 3] * w,
                        locations[x + 2] * h
                    ), paint
                )
                paint.style = Paint.Style.FILL
                val label = "${labels[classes[index].toInt()]} $score"
                canvas.drawText(
                    label,
                    locations[x + 1] * w,
                    locations[x] * h,
                    paint
                )
            }
        }
    }


    private fun objectsDetected(detectedObjects: MutableList<DetectedObject>) {
        for (detectedObject in detectedObjects) {
//            val boundingBox = detectedObject.boundingBox
//            val trackingId = detectedObject.trackingId
            for (label in detectedObject.labels) {
                val text = label.text
                val confidence = label.confidence
                println(text)
                if (objectDetectionCounter > 0 && objectSpeakCounter == 0) {
                    speak(text)
                    objectSpeakCounter = 50
                }
            }
        }
    }
}
