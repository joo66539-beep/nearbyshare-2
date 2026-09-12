package com.example.nearbyshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.nearbyshare.databinding.ActivityMainBinding
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opens straight into "looking for a nearby device" (both advertising and
 * discovering at once, no buttons needed). As soon as two phones running
 * this app are close to each other they connect automatically, and each
 * one is shown a choice: "استلام" (just wait for whatever arrives) or
 * "إرسال" (pick a photo / video / contact / text message to send).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var connectionsClient: ConnectionsClient

    private val connectedEndpoints = mutableSetOf<String>()
    private val serviceId = "com.example.nearbyshare.SERVICE_ID"
    private val strategy = Strategy.P2P_CLUSTER

    private val localEndpointName by lazy {
        "Phone-" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).takeLast(4)
    }

    // What kind of FILE payload is currently on its way in, keyed by payload id.
    private val incomingFileTypes = mutableMapOf<Long, String>()
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()
    private var pendingIncomingType: String? = null

    private var lastReceivedVideoUri: Uri? = null
    private var lastReceivedContactUri: Uri? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { sendFile(it, "IMAGE") }
        }
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { sendFile(it, "VIDEO") }
        }
    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.PickContact()) { uri: Uri? ->
            uri?.let { sendContact(it) }
        }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startBothRoles()
            } else {
                Toast.makeText(
                    this,
                    "لازم توافق على كل الصلاحيات عشان الميزة تشتغل",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectionsClient = Nearby.getConnectionsClient(this)

        binding.btnChooseReceive.setOnClickListener { showReceiveScreen() }
        binding.btnChooseSend.setOnClickListener { showSendScreen() }

        binding.btnSendText.setOnClickListener {
            val text = binding.editMessage.text.toString()
            if (text.isNotBlank()) sendText(text) else Toast.makeText(this, "اكتب رسالة الأول", Toast.LENGTH_SHORT).show()
        }
        binding.btnSendImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnSendVideo.setOnClickListener { pickVideoLauncher.launch("video/*") }
        binding.btnSendContact.setOnClickListener { pickContactLauncher.launch(null) }

        binding.btnPlayVideo.setOnClickListener {
            lastReceivedVideoUri?.let {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(it, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }
        binding.btnSaveContact.setOnClickListener {
            lastReceivedContactUri?.let {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(it, "text/x-vcard")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }
        binding.btnReset.setOnClickListener { resetToSearching() }

        // Open straight into searching - no buttons to press first.
        if (ensurePermissions()) startBothRoles()
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            perms += Manifest.permission.BLUETOOTH
            perms += Manifest.permission.BLUETOOTH_ADMIN
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return perms.toTypedArray()
    }

    private fun ensurePermissions(): Boolean {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isEmpty()) {
            true
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
            false
        }
    }

    // ---------------------------------------------------------------------
    // Auto advertise + discover at the same time
    // ---------------------------------------------------------------------

    private fun startBothRoles() {
        showSearchingScreen()
        val advOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(localEndpointName, serviceId, connectionLifecycleCallback, advOptions)
            .addOnFailureListener { e -> log("فشل advertising: ${e.message}") }

        val discOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, discOptions)
            .addOnFailureListener { e -> log("فشل discovery: ${e.message}") }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            log("لقيت جهاز: ${info.endpointName}")
            connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            log("الجهاز اختفى: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept - both phones already opened this same app on purpose.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints += endpointId
                // Once connected we don't need to keep advertising/discovering.
                connectionsClient.stopAdvertising()
                connectionsClient.stopDiscovery()
                showChoiceScreen()
                log("تم الاتصال ✅")
            } else {
                log("فشل الاتصال: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints -= endpointId
            log("الاتصال اتقطع")
            if (connectedEndpoints.isEmpty()) resetToSearching()
        }
    }

    // ---------------------------------------------------------------------
    // Screen states
    // ---------------------------------------------------------------------

    private fun showSearchingScreen() {
        binding.statusText.text = getString(R.string.status_searching)
        binding.searchingGroup.visibility = View.VISIBLE
        binding.choiceGroup.visibility = View.GONE
        binding.receiveGroup.visibility = View.GONE
        binding.sendGroup.visibility = View.GONE
        binding.btnReset.visibility = View.GONE
    }

    private fun showChoiceScreen() {
        binding.statusText.text = getString(R.string.status_connected)
        binding.searchingGroup.visibility = View.GONE
        binding.choiceGroup.visibility = View.VISIBLE
        binding.receiveGroup.visibility = View.GONE
        binding.sendGroup.visibility = View.GONE
        binding.btnReset.visibility = View.VISIBLE
    }

    private fun showReceiveScreen() {
        binding.choiceGroup.visibility = View.GONE
        binding.receiveGroup.visibility = View.VISIBLE
        binding.sendGroup.visibility = View.GONE
    }

    private fun showSendScreen() {
        binding.choiceGroup.visibility = View.GONE
        binding.receiveGroup.visibility = View.GONE
        binding.sendGroup.visibility = View.VISIBLE
    }

    private fun resetToSearching() {
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        binding.receivedImage.visibility = View.GONE
        binding.btnPlayVideo.visibility = View.GONE
        binding.btnSaveContact.visibility = View.GONE
        binding.logText.text = ""
        if (ensurePermissions()) startBothRoles()
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    private fun sendText(text: String) {
        if (connectedEndpoints.isEmpty()) return
        connectedEndpoints.forEach {
            connectionsClient.sendPayload(it, Payload.fromBytes("MSG:$text".toByteArray(Charsets.UTF_8)))
        }
        log("بعت نص: $text")
        binding.editMessage.setText("")
    }

    /** Sends a small "TYPE:xxx" header first, then the actual file payload. */
    private fun sendFile(uri: Uri, typeTag: String) {
        if (connectedEndpoints.isEmpty()) return
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: run {
            log("مقدرتش أفتح الملف")
            return
        }
        val filePayload = Payload.fromFile(pfd)
        connectedEndpoints.forEach {
            connectionsClient.sendPayload(it, Payload.fromBytes("TYPE:$typeTag".toByteArray(Charsets.UTF_8)))
            connectionsClient.sendPayload(it, filePayload)
        }
        log("بيبعت $typeTag...")
    }

    private fun sendContact(contactUri: Uri) {
        val cursor = contentResolver.query(
            contactUri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null
        ) ?: return
        cursor.use {
            if (it.moveToFirst()) {
                val lookupKey = it.getString(0)
                val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
                val afd = contentResolver.openAssetFileDescriptor(vcardUri, "r") ?: return
                if (connectedEndpoints.isEmpty()) return
                val payload = Payload.fromFile(afd.parcelFileDescriptor)
                connectedEndpoints.forEach { endpointId ->
                    connectionsClient.sendPayload(
                        endpointId,
                        Payload.fromBytes("TYPE:CONTACT".toByteArray(Charsets.UTF_8))
                    )
                    connectionsClient.sendPayload(endpointId, payload)
                }
                log("بيبعت جهة اتصال...")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Receiving
    // ---------------------------------------------------------------------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: ""
                    when {
                        text.startsWith("TYPE:") -> pendingIncomingType = text.removePrefix("TYPE:")
                        text.startsWith("MSG:") -> log("رسالة: ${text.removePrefix("MSG:")}")
                        else -> log("رسالة: $text")
                    }
                }
                Payload.Type.FILE -> {
                    incomingFileTypes[payload.id] = pendingIncomingType ?: "IMAGE"
                    incomingFilePayloads[payload.id] = payload
                    pendingIncomingType = null
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val payload = incomingFilePayloads.remove(update.payloadId) ?: return
                val type = incomingFileTypes.remove(update.payloadId) ?: "IMAGE"
                val uri = payload.asFile()?.asUri() ?: return
                runOnUiThread { handleReceivedFile(type, uri) }
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                incomingFilePayloads.remove(update.payloadId)
                incomingFileTypes.remove(update.payloadId)
                log("فشل الاستقبال")
            }
        }
    }

    private fun handleReceivedFile(type: String, uri: Uri) {
        when (type) {
            "IMAGE" -> {
                binding.receivedImage.visibility = View.VISIBLE
                binding.receivedImage.setImageURI(uri)
                log("صورة استلمت ✅")
            }
            "VIDEO" -> {
                lastReceivedVideoUri = uri
                binding.btnPlayVideo.visibility = View.VISIBLE
                log("فيديو استلم ✅ — دوس تشغيل عشان تشوفه")
            }
            "CONTACT" -> {
                lastReceivedContactUri = uri
                binding.btnSaveContact.visibility = View.VISIBLE
                log("جهة اتصال استلمت ✅ — دوس حفظ عشان تضيفها")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private fun log(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.logText.append("[$time] $line\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }
}
