package com.example.cameratesting

//import com.example.cameratesting.ml.TrainedOnLvis
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameratesting.databinding.ActivityMainBinding
import com.example.cameratesting.ml.SsdMobilenetV11Metadata1
import com.example.cameratesting.ui.theme.Alias
import com.example.cameratesting.ui.theme.AliasMatcher
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random


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
//    private lateinit var model: TrainedOnLvis

    private lateinit var imageProcessor: ImageProcessor

    private var imageCapture: ImageCapture? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var serverIPAddress: String
    private var serverPortNumber = 0
    private var photosPerCapture = 1
    private var debugMode = false

    private lateinit var fusedLocationClient : FusedLocationProviderClient;

    // channel of base64 encoded images
    private val imageChannel = Channel<String>()

    private var name = "IRIS"

    private val LOCATION_PERMISSION_REQUEST_CODE = 1

    private var currentSocket: Socket? = null
    private var heartbeatCounter: Int = 0
    private val heartbeatCounterLimit: Int = 50
    private val connectTimeoutMillis = 3000

    private lateinit var  barcodeScanner : BarcodeScanner
    private var barcodeCounter = 0
    private var barcodeExtraInstructions = ""
    private var barcodeFeedbackCooldown = 0
    private val barcodeThreshold = 50

    private var objectDetectionCounter = 0
    private var objectDetectionExtraInstructions = ""
    private var objectSpeakCounter = 0

    private var aliasMatcher = AliasMatcher();
    private var currentAddress: String? = null

    val objectDetectionOptions = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableClassification()  // Optional
        .build()
    val objectDetector = ObjectDetection.getClient(objectDetectionOptions)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        previewView = findViewById(R.id.viewFinder)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        initializeAliases()
        loadPreferences()
        checkLocationPermission()

        // Request permissions
        if (allPermissionsGranted()) {
            startCamera()
            initSpeechRecognition()
            initTextToSpeech()
            startBarcodeDetectionThread() // Start barcode detection thread
        } else {
            requestPermissions()
        }

        val audio = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, AudioManager.FLAG_SHOW_UI)

        startSendingTCPThread()
        startReceivingTCPThread()
        startHeartbeatThread()
        startObjectDetectionListener()

        // Set up the listeners for take photo and video capture buttons
        viewBinding.stopSpeakingButton.setOnClickListener { speak(" ") }

        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)
//        model = TrainedOnLvis.newInstance(this)

//        imageView = findViewById(R.id.viewFinder)

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            getLastKnownLocation()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                getLastKnownLocation()
            } else {
                println("Location permission not granted!")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getLastKnownLocation() {
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    location?.let {
                        val latitude = it.latitude
                        val longitude = it.longitude
                        println("You are at: "+latitude+" "+longitude)
                        getAddressFromLocation(latitude, longitude)
                    } ?: run {
                        Log.e("Location", "Location is null")
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e("Location", "Error getting location: ${exception.message}")
                }
        } catch (e: SecurityException) {
            Log.e("Location", "SecurityException: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getAddressFromLocation(latitude: Double, longitude: Double) {
        val geocoder = Geocoder(this, Locale.getDefault())
        val maxResults = 1

        geocoder.getFromLocation(latitude, longitude, maxResults, object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                if (addresses.isNotEmpty()) {
                    var address = addresses[0]
                    currentAddress = address.getAddressLine(0).replace(address.postalCode, "")
                } else {
                    currentAddress = null
                }
            }

            override fun onError(errorMessage: String?) {
                // Handle error
            }
        })
    }



    override fun onResume() {
        super.onResume()
        loadPreferences()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        stopBarcodeDetectionThread() // Stop barcode detection thread
    }

    // Barcode Detection Thread
    private fun startBarcodeDetectionThread() {
        barcodeScanner = BarcodeScanning.getClient()

        GlobalScope.launch(Dispatchers.Default) {
            while (true) {
                if (barcodeFeedbackCooldown > 0)
                    barcodeFeedbackCooldown--
                if (barcodeCounter > 0) {
                    barcodeCounter--
                    delay(100)
                }
                else
                    delay(200)
            }
        }
    }

    private fun stopBarcodeDetectionThread() {
        barcodeCounter = 0
    }

    private fun captureFrameFromCamera(): Bitmap? {
        val previewView = findViewById<PreviewView>(R.id.viewFinder)
        return previewView.bitmap
    }

    private fun processFrameForBarcode(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes[0]
                    val boundingBox = barcode.boundingBox
                    val rawValue = barcode.rawValue

                    if (boundingBox != null && barcodeCounter > 0) {
                        if (rawValue != null) {
                            sendUPCToBackend(rawValue)
                            speak("Barcode detected")
                            println("Detected barcode: $rawValue")
                            barcodeCounter=0
                        }
                        else
                            provideFeedback(boundingBox, bitmap.width, bitmap.height)
                    }
                }
            }
    }

    private fun provideFeedback(boundingBox: Rect, frameWidth: Int, frameHeight: Int) {
        if (barcodeFeedbackCooldown > 0) {
            barcodeFeedbackCooldown--
            return
        }
        val centerX = boundingBox.centerX()
        val centerY = boundingBox.centerY()

        val frameCenterX = frameWidth / 2
        val frameCenterY = frameHeight / 2

        barcodeFeedbackCooldown = 40
        when {
            centerX < frameCenterX - barcodeThreshold -> speak("Move the camera to the right")
            centerX > frameCenterX + barcodeThreshold -> speak("Move the camera to the left")
            centerY < frameCenterY - barcodeThreshold -> speak("Move the camera down")
            centerY > frameCenterY + barcodeThreshold -> speak("Move the camera up")
            else -> speak("Hold still")
        }
    }

    private fun isBarcodeCentered(boundingBox: Rect, frameWidth: Int, frameHeight: Int): Boolean {
        val centerX = boundingBox.centerX()
        val centerY = boundingBox.centerY()

        val frameCenterX = frameWidth / 2
        val frameCenterY = frameHeight / 2

        return (centerX in (frameCenterX - barcodeThreshold)..(frameCenterX + barcodeThreshold) &&
                centerY in (frameCenterY - barcodeThreshold)..(frameCenterY + barcodeThreshold))
    }

    private fun sendUPCToBackend(upcCode: String) {
        val json = "{\"command\":\"barcode\", \"upc_code\":\"$upcCode\", \"extra_instructions\":\"$barcodeExtraInstructions\"}"
        imageChannel.trySend(json)
    }

    private fun loadPreferences() {
        val sharedPreferences = getSharedPreferences("app_settings", MODE_PRIVATE)
        serverIPAddress = sharedPreferences.getString("server_ip_address", getString(R.string.server_default_ip_address)) ?: getString(R.string.server_default_ip_address)
        serverPortNumber = (sharedPreferences.getString("server_port_number", getString(R.string.server_default_port_number)) ?: getString(R.string.server_default_port_number)).toInt()
        photosPerCapture = (sharedPreferences.getString("photos_per_capture", getString(R.string.default_photos_per_capture)) ?: getString(R.string.default_photos_per_capture)).toInt()
        debugMode = (sharedPreferences.getBoolean("debug_mode", false))
        println("Server IP Address: $serverIPAddress")
        println("Server Port Number: $serverPortNumber")
        println("Photos Per Capture: $photosPerCapture")
        println("Debug Mode: $debugMode")
        if (debugMode)
            viewBinding.stopSpeakingButton.visibility = VISIBLE
        else
            viewBinding.stopSpeakingButton.visibility = GONE
    }

    private fun initializeAliases() {
        aliasMatcher.addAlias(Alias("explain", arrayListOf("explain", "give details", "elaborate")))
        aliasMatcher.addAlias(Alias("IRIS", arrayListOf("Iris", "Irish", "I guess", "I reached", "I just")))
        aliasMatcher.addAlias(Alias("what do you see", arrayListOf("What do you see", "Tell me what you see", "Describe the scene", "What’s in front of me", "What is in front of me", "Look around", "Explain the view", "Explain the scene")))
        aliasMatcher.addAlias(Alias("what is written", arrayListOf("What is written", "Read the text", "What does it say", "Tell me the text", "Read aloud", "Can you read this", "Read what is in front", "Read what's is in front", "Extract the words", "Scan the text")))
        aliasMatcher.addAlias(Alias("count the object", arrayListOf("Count the object", "How many objects?", "Tell me the number of objects", "How many things are there?", "Number of objects?")))
        aliasMatcher.addAlias(Alias("look for objects", arrayListOf("Look for object", "Find object", "Detect object", "Identify things", "Spot object", "Scan for object", "Recognize object", "What objects are there?")))
        aliasMatcher.addAlias(Alias("scan", arrayListOf("Scan", "barcode")))
        aliasMatcher.addAlias(Alias("change your name to", arrayListOf("change your name to")))
        aliasMatcher.addAlias(Alias("who is this", arrayListOf("Who is this", "Identify this person", "Recognize this face", "Tell me who this is", "Whose face is this?", "Can you recognize this person?", "Who am I looking at?")))
        aliasMatcher.addAlias(Alias("this is", arrayListOf("This is", "Remember this person as", "Add this face as", "Store this identity as", "Register this person as", "Memorize this person as", "Save this face as")))
        aliasMatcher.addAlias(Alias("where am I", arrayListOf("where am I", "tell me where I am", "find my location", "tell the current location")))
        aliasMatcher.addAlias(Alias("change your voice", arrayListOf("change your voice")))
        aliasMatcher.addAlias(Alias("Iris Help",arrayListOf("Help Iris")))
    }

    private fun connectToServer(ipAddress: String, port: Int): Socket? {
        return try {
            // Establish TCP connection
            val socket = Socket()
            socket.connect(InetSocketAddress(ipAddress, port), connectTimeoutMillis)
            println("Connected to server: $ipAddress:$port")
            socket
        } catch (e: Exception) {
            Log.e("Connecting to Server", "Error connecting to server: $e")
            null
        }
    }

    private fun startSendingTCPThread() {
        // Use coroutine scope to run the monitoring logic
        GlobalScope.launch {
            while (true) {
                try {
                    if (currentSocket != null) {
                        val socket = currentSocket ?: continue
                        val outputStream = socket.getOutputStream()
                        for (base64Image in imageChannel) {
                            outputStream.write(base64Image.toByteArray())
                            outputStream.flush()
                        }
                    }
                } catch (e : SocketException) {
                    println("Encountered SocketException in sending TCP thread!")
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
                    Log.d("Connecting to Server", "Trying to connect...")
                    try {
                        currentSocket = connectToServer(serverIPAddress, serverPortNumber)
                    } catch (e: Exception) {
                        Log.e("Connecting to Server", "Couldn't connect to server: $e")
                    }
                }
                if (i==0 && currentSocket != null) {
                    val socket = currentSocket ?: continue
                    val json_heartbeat="{\"command\":\"heartbeat\"}"
                    imageChannel.trySend(json_heartbeat)
                    synchronized (this) {
                        heartbeatCounter++
                        if (heartbeatCounter > heartbeatCounterLimit) {
                            heartbeatCounter -= 10
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

    private fun startObjectDetectionListener() {
        GlobalScope.launch {
            while (true) {
                if (objectDetectionCounter > 0)
                    objectDetectionCounter--
                if (objectSpeakCounter > 0)
                    objectSpeakCounter--
                delay(50)
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
        textToSpeech = TextToSpeech(applicationContext) { i ->
            if (i != TextToSpeech.ERROR) {
                textToSpeech.setLanguage(Locale("en", "UK"))
                speak("Welcome to iris!")
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
                              extraInstructions: String = "None", command : String, resolutionFactor: Int = 200) {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val tempFile = File(this.cacheDir, "$name.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object :
            ImageCapture.OnImageSavedCallback {

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
            extraInstructions=extraInstructions, command="ocr", resolutionFactor=1200)
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
//    private fun barcode_qrcode(extraInstructions : String = "") {
//        val imageCapture = imageCapture ?: return
//
//        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
//        val tempFile = File(this.cacheDir, "$name.jpg")
//        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
//
//        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : OnImageSavedCallback {
//
//            // This method is invoked when the image is saved successfully
////            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
////                // Path of the saved image in the temporary directory
////                val savedPath = tempFile.absolutePath
////                println("Image saved at: $savedPath")
////                val bitmap = BitmapFactory.decodeFile(savedPath)
////                val inputImage = InputImage.fromBitmap(bitmap, 0)
////                var gotBarcode = false
////                val result = scanner.process(inputImage)
////                    .addOnSuccessListener { barcodes ->
////                        gotBarcode = true
////                        for (barcode in barcodes) {
////                            barcodeCounter = 0
////                            val bounds = barcode.boundingBox
////                            val corners = barcode.cornerPoints
////
////                            val rawValue = barcode.rawValue
////
////                            val valueType = barcode.valueType
////                            // See API reference for complete list of supported types
////                            when (valueType) {
////                                Barcode.TYPE_PRODUCT -> {
////                                    speak("Found a product barcode. Checking for product information...")
////                                    val json ="{\"command\":\"barcode\",\"barcode_raw_value\":\"$rawValue\",\"extra_instructions\":\"$extraInstructions\"}"
////                                    imageChannel.trySend(json)
////                                }
////                                Barcode.TYPE_URL -> {
////                                    speak("URL barcode: $rawValue")
////                                }
////                            }
////                        }
////                    }
////                    .addOnFailureListener {
////                    }
//////                if (!gotBarcode)
//////                    speak("no barcode found")
//            }
//
//            // This method is invoked if there is an error during image capture
//            override fun onError(exception: ImageCaptureException) {
//                // Handle errors in image capture
//                exception.printStackTrace()
//            }
//        })
//    }


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

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object :
            ImageCapture.OnImageSavedCallback {

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
        val utteranceId = "TTS_${System.currentTimeMillis()}"
        textToSpeech.speak(text,TextToSpeech.QUEUE_FLUSH,null, utteranceId);
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
            "Wait a moment...",
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
            println("Speech Possibility: $possibility")
            if (match)      // if a command has already been identified for one of the
                break       // possible sentences, don't look at the other possibilities
            if (!aliasMatcher.contains(possibility, name))
                continue
            var str = aliasMatcher.remove(possibility, name)

            if (str != null) {
                println(str)

                if (aliasMatcher.contains(str, "what do you see")) {
                    val extraInstructions = aliasMatcher.remove(str, "what do you see") ?: ""
                    describeScene(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                else if (aliasMatcher.contains(str, "what is written")) {
                    val extraInstructions = aliasMatcher.remove(str, "what is written") ?: ""
                    ocr(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                else if (aliasMatcher.contains(str, "count the object")) {
                    val extraInstructions = aliasMatcher.remove(str, "count the object") ?: ""
                    count_objects(extraInstructions=extraInstructions)
                    match = true
                }
                else if (aliasMatcher.contains(str, "look for objects")) {
                    if (str.contains("stop")) {
                        objectDetectionCounter=0
                        objectSpeakCounter=0
                        speak("No longer looking for objects")
                    }
                    else {
                        objectDetectionExtraInstructions = aliasMatcher.remove(str, "look for objects") ?: ""
                        objectDetectionCounter=1000
                        objectSpeakCounter=50
                        speak("Looking for objects")
                    }
                    match = true
                }
                else if (aliasMatcher.contains(str, "scan")) {
                    if (str.contains("stop")) {
                        barcodeCounter=0
                        speak("No longer looking for barcodes")
                    }
                    else {
                        barcodeExtraInstructions = aliasMatcher.remove(str, "look for objects") ?: ""
                        barcodeCounter=1000
                        speak("Looking for barcodes")
                    }
                    match = true
                }
                else if (aliasMatcher.contains(str, "who is this")) {
                    val extraInstructions = aliasMatcher.remove(str, "who is this") ?: ""
                    face_recognition(extraInstructions=extraInstructions)
                    lookingMessage()
                    match = true
                }
                else if (aliasMatcher.contains(str, "this is")) {
                    val faceName = aliasMatcher.remove(str, "this is") ?: ""
                    add_face(name = faceName)
                    lookingMessage()
                    match = true
                }
                else if (aliasMatcher.contains(str, "change your name to")) {
                    name = aliasMatcher.remove(str, "change your name to") ?: name
                    speak("My new name is $name")
                    match = true
                }
                else if (aliasMatcher.contains(str, "where am I")) {
                    speakLocation()
                    match = true
                }
                else if (aliasMatcher.contains(str, "change your voice")) {
                    changeVoice()
                    match = true
                }
                else if (aliasMatcher.contains(str, "help")) {
                    help()
                }
                println(aliasMatcher.contains("explain this explain that", "explain"))
                if (aliasMatcher.contains(str, "explain")) {
                    val json="{\"command\":\"llm\",\"prompt\":\"$str\"}"
                    imageChannel.trySend(json)
                    lookingMessage()
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
                if (str.contains("say")) {
                    speak(str.substringAfter("say"))
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
                50
            )
        }
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("en", "IN"))
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
                                if (objectDetectionCounter > 0)
                                    processFrameForObjectDetection(bitmap)
                                if (barcodeCounter > 0)
                                    processFrameForBarcode(bitmap)
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


    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
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
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startCamera()
                startBarcodeDetectionThread() // Start barcode detection after permissions are granted
            }
        }

    private fun processFrameForObjectDetection(bitmap: Bitmap) {
        val image = TensorImage.fromBitmap(bitmap)
        val processedImage = imageProcessor.process(image)

        // Run object detection
        val output = model.process(processedImage)
        val locations = output.locationsAsTensorBuffer.floatArray
        val classes = output.classesAsTensorBuffer.floatArray
        val scores = output.scoresAsTensorBuffer.floatArray

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
            if (objectDetectionCounter > 0 && objectSpeakCounter == 0 && maxScore > 0.2) {
                speak(mostDominantObject!!)
                objectSpeakCounter = 50
            }
        }
    }

    private fun speakLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getLastKnownLocation()
            println("Location: "+currentAddress)
            if (currentAddress != null)
                speak("You are near $currentAddress")
            else
                speak ("I can't find your location")
        }
    }

    private fun help() {
        speak("Say look for objects for Object Detection Command,"+
            "Say what do you see for Scene Description Command,"+
            "Say scan barcode for Barcode Scanning Command,"+
            "Say who is this for Face Detection Command,"+
            "Say where am I for location command")
    }
}