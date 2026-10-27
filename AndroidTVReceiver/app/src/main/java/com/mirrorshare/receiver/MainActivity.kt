package com.mirrorshare.receiver

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.math.max


class MainActivity : Activity() {

    companion object {

        private const val VIDEO_PORT = 5000
        private const val AUDIO_PORT = 5001
        private const val DISCOVERY_PORT = 5002

        private const val DISCOVERY_REQUEST =
            "WIRELESS_DISPLAY_DISCOVER"

        private const val DISCOVERY_RESPONSE =
            "WIRELESS_DISPLAY_RECEIVER"
    }


    // =========================================================
    // UI
    // =========================================================

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var waitingScreen: LinearLayout

    private lateinit var ipLabel: TextView
    private lateinit var videoStatusText: TextView
    private lateinit var audioStatusText: TextView

    private lateinit var videoStatusDot: TextView
    private lateinit var audioStatusDot: TextView


    // =========================================================
    // RECEIVERS
    // =========================================================

    private var videoReceiver: H264TcpReceiver? = null
    private var audioReceiver: PcmAudioReceiver? = null


    // =========================================================
    // DISCOVERY
    // =========================================================

    @Volatile
    private var discoveryRunning = false

    private var discoveryThread: Thread? = null
    private var discoverySocket: DatagramSocket? = null


    // =========================================================
    // COLORS
    // =========================================================

    // Light glass / cyan-violet palette matching the Windows sender.
    private val black = Color.rgb(238, 242, 255)
    private val panel = Color.rgb(255, 255, 255)
    private val violet = Color.rgb(116, 104, 247)
    private val violetLight = Color.rgb(103, 95, 228)
    private val blue = Color.rgb(59, 130, 246)
    private val cyan = Color.rgb(39, 199, 234)
    private val green = Color.rgb(33, 185, 133)
    private val yellow = Color.rgb(244, 166, 42)
    private val muted = Color.rgb(133, 145, 169)
    private val white = Color.rgb(40, 48, 71)


    // =========================================================
    // ACTIVITY
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        enableFullScreen()

        buildInterface()

        startAudioReceiver()

        startDiscoveryServer()
    }


    override fun onResume() {

        super.onResume()

        enableFullScreen()
    }


    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {

        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            enableFullScreen()
        }
    }


    override fun onDestroy() {

        stopDiscoveryServer()

        videoReceiver?.stopReceiver()

        audioReceiver?.stopReceiver()

        super.onDestroy()
    }


    // =========================================================
    // FULL SCREEN
    // =========================================================

    @Suppress("DEPRECATION")
    private fun enableFullScreen() {

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }


    // =========================================================
    // BUILD UI
    // =========================================================

    private fun buildInterface() {
        root = FrameLayout(this)
        root.setBackgroundColor(black)
        setContentView(root)

        // Video always stays beneath the idle screen.
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startVideoReceiver(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                videoReceiver?.stopReceiver()
                videoReceiver = null
            }
        })

        waitingScreen = LinearLayout(this)
        waitingScreen.orientation = LinearLayout.VERTICAL
        waitingScreen.gravity = Gravity.CENTER_HORIZONTAL
        waitingScreen.setPadding(dp(58), dp(30), dp(58), dp(28))

        val bg = android.graphics.drawable.GradientDrawable()
        bg.orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
        bg.colors = intArrayOf(
            Color.rgb(249, 252, 255),
            Color.rgb(232, 242, 255),
            Color.rgb(245, 241, 255)
        )
        waitingScreen.background = bg
        root.addView(
            waitingScreen,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Mirror Share wordmark centered at the top.
        val brand = ImageView(this)
        brand.setImageResource(R.drawable.mirror_share_logo)
        brand.scaleType = ImageView.ScaleType.CENTER_INSIDE
        waitingScreen.addView(
            brand,
            LinearLayout.LayoutParams(dp(500), dp(118)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            }
        )

        val contentRow = LinearLayout(this)
        contentRow.orientation = LinearLayout.HORIZONTAL
        contentRow.gravity = Gravity.CENTER
        waitingScreen.addView(
            contentRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // Left video status card.
        val videoCard = createLargeStatusCard("VIDEO LINK", "READY", blue)
        videoStatusDot = videoCard.second.first
        videoStatusText = videoCard.second.second
        contentRow.addView(
            videoCard.first,
            LinearLayout.LayoutParams(0, dp(210), 0.23f).apply {
                rightMargin = dp(22)
            }
        )

        // Main ready-to-cast card.
        val mainCard = LinearLayout(this)
        mainCard.orientation = LinearLayout.VERTICAL
        mainCard.gravity = Gravity.CENTER_HORIZONTAL
        mainCard.setPadding(dp(34), dp(24), dp(34), dp(24))
        mainCard.background = roundedBackground(
            Color.argb(238, 255, 255, 255),
            Color.rgb(215, 227, 243),
            32
        )

        val symbolHalo = FrameLayout(this)
        symbolHalo.background = roundedBackground(
            Color.rgb(237, 248, 255),
            Color.rgb(116, 207, 250),
            72
        )
        val symbol = ImageView(this)
        symbol.setImageResource(R.drawable.mirror_share_symbol)
        symbol.scaleType = ImageView.ScaleType.CENTER_INSIDE
        symbol.adjustViewBounds = true
        symbol.setPadding(dp(24), dp(24), dp(24), dp(24))
        symbol.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        symbolHalo.addView(
            symbol,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        mainCard.addView(symbolHalo, LinearLayout.LayoutParams(dp(142), dp(142)))

        val ready = createText("READY TO CAST", 36, Color.rgb(13, 54, 111), true)
        ready.gravity = Gravity.CENTER
        mainCard.addView(
            ready,
            marginParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0, 17, 0, 0
            )
        )

        val desc = createText("Wirelessly display your screen to this receiver", 15, muted, false)
        desc.gravity = Gravity.CENTER
        mainCard.addView(
            desc,
            marginParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0, 4, 0, 16
            )
        )

        val ipCard = LinearLayout(this)
        ipCard.orientation = LinearLayout.VERTICAL
        ipCard.gravity = Gravity.CENTER
        ipCard.setPadding(dp(26), dp(12), dp(26), dp(12))
        ipCard.background = roundedBackground(
            Color.rgb(249, 252, 255),
            Color.rgb(210, 223, 242),
            20
        )
        val ipTitle = createText("RECEIVER IP ADDRESS", 10, muted, true)
        ipTitle.gravity = Gravity.CENTER
        ipCard.addView(ipTitle)
        ipLabel = createText(getLocalIpAddress(), 32, Color.rgb(36, 104, 215), true)
        ipLabel.gravity = Gravity.CENTER
        ipCard.addView(
            ipLabel,
            marginParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 0
            )
        )
        mainCard.addView(
            ipCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(100))
        )

        contentRow.addView(
            mainCard,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.54f)
        )

        // Right audio status card.
        val audioCard = createLargeStatusCard("AUDIO LINK", "READY", violet)
        audioStatusDot = audioCard.second.first
        audioStatusText = audioCard.second.second
        contentRow.addView(
            audioCard.first,
            LinearLayout.LayoutParams(0, dp(210), 0.23f).apply {
                leftMargin = dp(22)
            }
        )

        // Bottom capability pills.
        val badges = LinearLayout(this)
        badges.orientation = LinearLayout.HORIZONTAL
        badges.gravity = Gravity.CENTER
        addBadge(badges, "●  RECEIVER ONLINE")
        addBadge(badges, "1080P")
        addBadge(badges, "60 FPS")
        addBadge(badges, "H.264")
        addBadge(badges, "LOW LATENCY")
        waitingScreen.addView(
            badges,
            marginParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0, 18, 0, 0
            )
        )

        val footer = createText(
            "VIDEO TCP 5000   •   AUDIO TCP 5001   •   AUTOMATIC DISCOVERY UDP 5002",
            9,
            muted,
            true
        )
        footer.gravity = Gravity.CENTER
        waitingScreen.addView(
            footer,
            marginParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0, 11, 0, 0
            )
        )
    }

    private fun createLargeStatusCard(
        title: String,
        status: String,
        accent: Int
    ): Pair<LinearLayout, Pair<TextView, TextView>> {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.setPadding(dp(22), dp(18), dp(22), dp(18))
        card.background = roundedBackground(
            Color.argb(232, 255, 255, 255),
            Color.rgb(218, 228, 243),
            28
        )

        val icon = createText(if (title.startsWith("VIDEO")) "▣" else "◖", 36, accent, true)
        icon.gravity = Gravity.CENTER
        card.addView(icon)

        val titleText = createText(title, 15, Color.rgb(14, 54, 107), true)
        titleText.gravity = Gravity.CENTER
        card.addView(
            titleText,
            marginParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0, 12, 0, 0
            )
        )

        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER
        val dot = createText("●", 18, green, true)
        val statusText = createText(status, 12, muted, true)
        line.addView(dot)
        line.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(7) }
        )
        card.addView(
            line,
            marginParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0, 10, 0, 0
            )
        )
        return Pair(card, Pair(dot, statusText))
    }

    // =========================================================
    // CREATE STATUS CARD
    // =========================================================

    private fun createStatusCard(
        title: String,
        status: String,
        accent: Int
    ): Pair<
            LinearLayout,
            Pair<TextView, TextView>
            > {

        val card =
            LinearLayout(this)

        card.orientation =
            LinearLayout.HORIZONTAL

        card.gravity =
            Gravity.CENTER_VERTICAL

        card.setPadding(
            dp(18),
            dp(10),
            dp(18),
            dp(10)
        )

        card.background =
            roundedBackground(
                panel,
                Color.rgb(224, 230, 243),
                14
            )


        val dot =
            createText(
                "●",
                18,
                yellow,
                true
            )


        card.addView(dot)


        val textArea =
            LinearLayout(this)

        textArea.orientation =
            LinearLayout.VERTICAL


        val titleText =
            createText(
                title,
                9,
                muted,
                true
            )


        val statusText =
            createText(
                status,
                14,
                accent,
                true
            )


        textArea.addView(titleText)

        textArea.addView(
            statusText,
            marginParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0,
                5,
                0,
                0
            )
        )


        card.addView(
            textArea,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {

                leftMargin =
                    dp(14)
            }
        )


        return Pair(
            card,
            Pair(
                dot,
                statusText
            )
        )
    }


    // =========================================================
    // BADGE
    // =========================================================

    private fun addBadge(
        parent: LinearLayout,
        text: String
    ) {

        val badge =
            createText(
                text,
                9,
                violetLight,
                true
            )

        badge.gravity =
            Gravity.CENTER

        badge.setPadding(
            dp(13),
            dp(7),
            dp(13),
            dp(7)
        )

        badge.background =
            roundedBackground(
                Color.rgb(238, 242, 255),
                Color.rgb(205, 215, 245),
                10
            )


        parent.addView(
            badge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {

                leftMargin =
                    dp(5)

                rightMargin =
                    dp(5)
            }
        )
    }


    // =========================================================
    // TEXT HELPER
    // =========================================================

    private fun createText(
        text: String,
        size: Int,
        color: Int,
        bold: Boolean
    ): TextView {

        val view =
            TextView(this)

        view.text =
            text

        view.textSize =
            size.toFloat()

        view.setTextColor(
            color
        )

        view.typeface =
            if (bold) {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }

        return view
    }


    // =========================================================
    // BACKGROUND HELPER
    // =========================================================

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        radius: Int
    ): android.graphics.drawable.GradientDrawable {

        val drawable =
            android.graphics.drawable.GradientDrawable()

        drawable.setColor(
            fillColor
        )

        drawable.cornerRadius =
            dp(radius).toFloat()

        drawable.setStroke(
            dp(1),
            strokeColor
        )

        return drawable
    }


    // =========================================================
    // MARGIN HELPER
    // =========================================================

    private fun marginParams(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            width,
            height
        ).apply {

            setMargins(
                dp(left),
                dp(top),
                dp(right),
                dp(bottom)
            )
        }
    }


    // =========================================================
    // DP
    // =========================================================

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        resources.displayMetrics.density
                ).toInt()
    }


    // =========================================================
    // START VIDEO RECEIVER
    // =========================================================

    private fun startVideoReceiver(
        surface: Surface
    ) {

        if (videoReceiver != null) {
            return
        }


        videoReceiver =
            H264TcpReceiver(
                port = VIDEO_PORT,
                width = 1920,
                height = 1080,
                fps = 60,
                surface = surface
            ) { status ->

                runOnUiThread {

                    when (status) {

                        "RECEIVING" -> {

                            videoStatusDot.setTextColor(
                                green
                            )

                            videoStatusText.text =
                                "RECEIVING"

                            videoStatusText.setTextColor(
                                green
                            )

                            // Cover disappears only once
                            // actual decoded video is rendered.
                            waitingScreen.visibility =
                                View.GONE
                        }


                        "CONNECTED" -> {

                            videoStatusDot.setTextColor(
                                cyan
                            )

                            videoStatusText.text =
                                "CONNECTING"

                            videoStatusText.setTextColor(
                                cyan
                            )
                        }


                        else -> {

                            videoStatusDot.setTextColor(
                                yellow
                            )

                            videoStatusText.text =
                                "WAITING"

                            videoStatusText.setTextColor(
                                violetLight
                            )

                            // Covers stale last video frame.
                            waitingScreen.visibility =
                                View.VISIBLE

                            enableFullScreen()
                        }
                    }
                }
            }


        videoReceiver?.start()
    }


    // =========================================================
    // START AUDIO RECEIVER
    // =========================================================

    private fun startAudioReceiver() {

        if (audioReceiver != null) {
            return
        }


        audioReceiver =
            PcmAudioReceiver(
                port = AUDIO_PORT
            ) { status ->

                runOnUiThread {

                    when (status) {

                        "RECEIVING" -> {

                            audioStatusDot.setTextColor(
                                green
                            )

                            audioStatusText.text =
                                "RECEIVING"

                            audioStatusText.setTextColor(
                                green
                            )
                        }


                        else -> {

                            audioStatusDot.setTextColor(
                                yellow
                            )

                            audioStatusText.text =
                                "WAITING"

                            audioStatusText.setTextColor(
                                blue
                            )
                        }
                    }
                }
            }


        audioReceiver?.start()
    }


    // =========================================================
    // AUTOMATIC DISCOVERY SERVER
    // =========================================================

    private fun startDiscoveryServer() {

        if (discoveryRunning) {
            return
        }


        discoveryRunning =
            true


        discoveryThread =
            Thread {

                try {

                    val socket =
                        DatagramSocket(null)

                    discoverySocket =
                        socket


                    socket.reuseAddress =
                        true

                    socket.broadcast =
                        true


                    socket.bind(
                        InetSocketAddress(
                            DISCOVERY_PORT
                        )
                    )


                    val buffer =
                        ByteArray(2048)


                    while (discoveryRunning) {

                        try {

                            val packet =
                                DatagramPacket(
                                    buffer,
                                    buffer.size
                                )


                            socket.receive(
                                packet
                            )


                            val message =
                                String(
                                    packet.data,
                                    0,
                                    packet.length,
                                    StandardCharsets.UTF_8
                                ).trim()


                            if (
                                message ==
                                DISCOVERY_REQUEST
                            ) {

                                val manufacturer =
                                    Build.MANUFACTURER
                                        ?.trim()
                                        ?.replace(
                                            "|",
                                            " "
                                        )
                                        ?: "Android"


                                val model =
                                    Build.MODEL
                                        ?.trim()
                                        ?.replace(
                                            "|",
                                            " "
                                        )
                                        ?: "TV"


                                val deviceName =
                                    "$manufacturer $model"
                                        .trim()


                                val receiverId =
                                    try {
                                        Settings.Secure.getString(
                                            contentResolver,
                                            Settings.Secure.ANDROID_ID
                                        ) ?: deviceName
                                    } catch (_: Exception) {
                                        deviceName
                                    }

                                val response =
                                    "$DISCOVERY_RESPONSE|" +
                                            "$deviceName|" +
                                            "$VIDEO_PORT|" +
                                            "$AUDIO_PORT|" +
                                            "$receiverId|" +
                                            "1.1|" +
                                            "available"


                                val responseBytes =
                                    response.toByteArray(
                                        StandardCharsets.UTF_8
                                    )


                                val responsePacket =
                                    DatagramPacket(
                                        responseBytes,
                                        responseBytes.size,
                                        packet.address,
                                        packet.port
                                    )


                                socket.send(
                                    responsePacket
                                )
                            }

                        } catch (
                            e: Exception
                        ) {

                            if (
                                discoveryRunning
                            ) {

                                e.printStackTrace()
                            }
                        }
                    }

                } catch (
                    e: Exception
                ) {

                    if (
                        discoveryRunning
                    ) {

                        e.printStackTrace()
                    }

                } finally {

                    try {

                        discoverySocket
                            ?.close()

                    } catch (
                        _: Exception
                    ) {
                    }


                    discoverySocket =
                        null
                }

            }.apply {

                name =
                    "MirrorShareDiscovery"

                isDaemon =
                    true

                start()
            }
    }


    // =========================================================
    // STOP DISCOVERY
    // =========================================================

    private fun stopDiscoveryServer() {

        discoveryRunning =
            false


        try {

            discoverySocket
                ?.close()

        } catch (
            _: Exception
        ) {
        }


        try {

            discoveryThread
                ?.interrupt()

        } catch (
            _: Exception
        ) {
        }


        discoverySocket =
            null

        discoveryThread =
            null
    }


    // =========================================================
    // GET LOCAL IP
    // =========================================================

    private fun getLocalIpAddress(): String {

        try {

            val interfaces =
                Collections.list(
                    NetworkInterface
                        .getNetworkInterfaces()
                )


            var fallback:
                    String? = null


            for (
            networkInterface
            in interfaces
            ) {

                if (
                    !networkInterface.isUp ||
                    networkInterface.isLoopback
                ) {
                    continue
                }


                val addresses =
                    Collections.list(
                        networkInterface
                            .inetAddresses
                    )


                for (
                address
                in addresses
                ) {

                    if (
                        address is Inet4Address &&
                        !address.isLoopbackAddress
                    ) {

                        val ip =
                            address.hostAddress
                                ?: continue


                        if (
                            address.isSiteLocalAddress
                        ) {

                            return ip
                        }


                        if (
                            fallback == null
                        ) {

                            fallback =
                                ip
                        }
                    }
                }
            }


            return fallback
                ?: "Waiting for network..."

        } catch (
            e: Exception
        ) {

            return "Waiting for network..."
        }
    }
}


// =============================================================
// H264 TCP RECEIVER
// =============================================================

class H264TcpReceiver(

    private val port: Int,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val surface: Surface,
    private val statusCallback: (String) -> Unit

) {

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null


    fun start() {

        if (running) {
            return
        }

        running = true

        thread =
            Thread {
                runServer()
            }.apply {
                name = "H264TcpReceiver"
                priority = Thread.MAX_PRIORITY
                start()
            }
    }


    fun stopReceiver() {

        running = false

        closeQuietly(clientSocket)
        closeQuietly(serverSocket)

        clientSocket = null
        serverSocket = null

        val receiverThread = thread

        if (
            receiverThread != null &&
            receiverThread !== Thread.currentThread()
        ) {
            try {
                receiverThread.join(1200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        thread = null
    }


    private fun runServer() {

        try {

            serverSocket =
                ServerSocket().apply {

                    reuseAddress = true

                    bind(
                        InetSocketAddress(
                            port
                        )
                    )
                }

            while (running) {

                statusCallback("WAITING")

                try {

                    val socket =
                        serverSocket
                            ?.accept()
                            ?: break

                    if (!running) {
                        closeQuietly(socket)
                        break
                    }

                    clientSocket = socket

                    socket.tcpNoDelay = true
                    socket.keepAlive = true

                    // Keep the video queue small enough for low latency,
                    // while still allowing a full high-bitrate frame burst.
                    socket.receiveBufferSize =
                        64 * 1024

                    statusCallback("CONNECTED")

                    // processStream owns and releases the MediaCodec for
                    // this connection. A new connection always gets a
                    // completely fresh decoder.
                    processStream(socket)

                } catch (e: Exception) {

                    if (running) {
                        e.printStackTrace()
                    }

                } finally {

                    closeQuietly(clientSocket)
                    clientSocket = null

                    if (running) {
                        statusCallback("WAITING")
                    }
                }
            }

        } catch (e: Exception) {

            if (running) {
                e.printStackTrace()
            }

        } finally {

            closeQuietly(serverSocket)

            serverSocket = null

            if (running) {
                statusCallback("WAITING")
            }
        }
    }


    private fun processStream(
        socket: Socket
    ) {

        var codec: MediaCodec? = null

        try {

            val input =
                socket.getInputStream()

            val accumulator =
                ByteArrayOutputStream(
                    256 * 1024
                )

            val readBuffer =
                ByteArray(
                    64 * 1024
                )

            var sps: ByteArray? = null
            var pps: ByteArray? = null
            var firstFrameRendered = false

            while (running) {

                val count =
                    input.read(
                        readBuffer
                    )

                if (count <= 0) {
                    break
                }

                accumulator.write(
                    readBuffer,
                    0,
                    count
                )

                val data =
                    accumulator
                        .toByteArray()

                val starts =
                    findStartCodes(
                        data
                    )

                if (starts.size < 2) {

                    // Corrupt/incomplete input must never grow forever.
                    if (
                        accumulator.size() >
                        4 * 1024 * 1024
                    ) {
                        accumulator.reset()
                    }

                    continue
                }

                for (
                    i in 0 until
                        starts.size - 1
                ) {

                    val current =
                        starts[i]

                    val next =
                        starts[i + 1]

                    val payloadStart =
                        current.first +
                            current.second

                    if (
                        payloadStart >=
                        next.first
                    ) {
                        continue
                    }

                    val payload =
                        data.copyOfRange(
                            payloadStart,
                            next.first
                        )

                    if (payload.isEmpty()) {
                        continue
                    }

                    val nalType =
                        payload[0]
                            .toInt() and
                            0x1F

                    when (nalType) {

                        7 -> {
                            sps =
                                annexB(
                                    payload
                                )
                        }

                        8 -> {
                            pps =
                                annexB(
                                    payload
                                )
                        }
                    }

                    if (
                        codec == null &&
                        sps != null &&
                        pps != null
                    ) {

                        codec =
                            createDecoder(
                                sps,
                                pps
                            )
                    }

                    if (
                        codec != null &&
                        nalType != 7 &&
                        nalType != 8
                    ) {

                        queueNal(
                            codec,
                            annexB(
                                payload
                            )
                        )

                        val rendered =
                            drainDecoder(
                                codec
                            )

                        if (
                            rendered &&
                            !firstFrameRendered
                        ) {

                            firstFrameRendered = true
                            statusCallback("RECEIVING")
                        }
                    }
                }

                val lastStart =
                    starts.last().first

                accumulator.reset()

                if (
                    lastStart <
                    data.size
                ) {

                    accumulator.write(
                        data,
                        lastStart,
                        data.size -
                            lastStart
                    )
                }
            }

        } finally {

            // This is the key reconnect fix: decoder ownership is local
            // to one TCP session and it is always released, including
            // when input.read()/queueInputBuffer throws unexpectedly.
            releaseCodec(codec)
        }
    }


    private fun releaseCodec(
        codec: MediaCodec?
    ) {

        if (codec == null) {
            return
        }

        try {
            codec.flush()
        } catch (_: Exception) {
        }

        try {
            codec.stop()
        } catch (_: Exception) {
        }

        try {
            codec.release()
        } catch (_: Exception) {
        }
    }


    private fun createDecoder(
        sps: ByteArray,
        pps: ByteArray
    ): MediaCodec {

        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            )

        format.setByteBuffer(
            "csd-0",
            ByteBuffer.wrap(
                sps
            )
        )

        format.setByteBuffer(
            "csd-1",
            ByteBuffer.wrap(
                pps
            )
        )

        format.setInteger(
            MediaFormat.KEY_MAX_INPUT_SIZE,
            2 * 1024 * 1024
        )

        format.setInteger(
            MediaFormat.KEY_FRAME_RATE,
            fps
        )

        try {
            format.setInteger(
                MediaFormat.KEY_PRIORITY,
                0
            )
        } catch (_: Exception) {
        }

        try {
            format.setFloat(
                MediaFormat.KEY_OPERATING_RATE,
                fps.toFloat()
            )
        } catch (_: Exception) {
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {

            try {
                format.setInteger(
                    "low-latency",
                    1
                )
            } catch (_: Exception) {
            }
        }

        val codec =
            MediaCodec.createDecoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC
            )

        codec.configure(
            format,
            surface,
            null,
            0
        )

        codec.start()

        return codec
    }


    private fun queueNal(
        codec: MediaCodec,
        nal: ByteArray
    ) {

        var offset = 0

        while (
            offset <
            nal.size &&
            running
        ) {

            val inputIndex =
                codec.dequeueInputBuffer(
                    10_000
                )

            if (inputIndex < 0) {

                drainDecoder(
                    codec
                )

                continue
            }

            val inputBuffer =
                codec.getInputBuffer(
                    inputIndex
                )
                    ?: continue

            inputBuffer.clear()

            val writable =
                minOf(
                    inputBuffer.remaining(),
                    nal.size -
                        offset
                )

            inputBuffer.put(
                nal,
                offset,
                writable
            )

            codec.queueInputBuffer(
                inputIndex,
                0,
                writable,
                System.nanoTime() /
                    1000L,
                0
            )

            offset += writable
        }
    }


    private fun drainDecoder(
        codec: MediaCodec
    ): Boolean {

        var rendered = false

        val info =
            MediaCodec.BufferInfo()

        while (true) {

            val outputIndex =
                codec.dequeueOutputBuffer(
                    info,
                    0
                )

            when {

                outputIndex >= 0 -> {

                    codec.releaseOutputBuffer(
                        outputIndex,
                        true
                    )

                    rendered = true
                }

                outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // No action required.
                }

                outputIndex ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break
                }

                else -> {
                    break
                }
            }
        }

        return rendered
    }


    private fun annexB(
        payload: ByteArray
    ): ByteArray {

        val output =
            ByteArray(
                payload.size + 4
            )

        output[0] = 0x00
        output[1] = 0x00
        output[2] = 0x00
        output[3] = 0x01

        System.arraycopy(
            payload,
            0,
            output,
            4,
            payload.size
        )

        return output
    }


    private fun findStartCodes(
        data: ByteArray
    ): List<Pair<Int, Int>> {

        val result =
            ArrayList<Pair<Int, Int>>()

        var i = 0

        while (
            i <
            data.size - 2
        ) {

            // 00 00 00 01
            if (
                i + 3 <
                data.size &&
                data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 &&
                data[i + 3].toInt() == 1
            ) {

                result.add(
                    Pair(
                        i,
                        4
                    )
                )

                i += 4
                continue
            }

            // 00 00 01
            if (
                data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1
            ) {

                result.add(
                    Pair(
                        i,
                        3
                    )
                )

                i += 3
                continue
            }

            i++
        }

        return result
    }


    private fun closeQuietly(
        socket: Socket?
    ) {

        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }


    private fun closeQuietly(
        socket: ServerSocket?
    ) {

        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}


// =============================================================
// PCM AUDIO RECEIVER
// =============================================================

class PcmAudioReceiver(

    private val port: Int,
    private val statusCallback: (String) -> Unit

) {

    companion object {

        private const val SAMPLE_RATE =
            44_100

        private const val CHANNEL_COUNT =
            2

        private const val BYTES_PER_SAMPLE =
            2

        private const val FRAME_BYTES =
            CHANNEL_COUNT *
                BYTES_PER_SAMPLE

        // 4096 bytes is about 23 ms of stereo 44.1 kHz PCM.
        private const val READ_BUFFER_BYTES =
            4096
    }


    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null


    fun start() {

        if (running) {
            return
        }

        running = true

        thread =
            Thread {
                runServer()
            }.apply {
                name = "PcmAudioReceiver"
                priority = Thread.MAX_PRIORITY
                start()
            }
    }


    fun stopReceiver() {

        running = false

        closeQuietly(clientSocket)
        closeQuietly(serverSocket)

        clientSocket = null
        serverSocket = null

        val receiverThread = thread

        if (
            receiverThread != null &&
            receiverThread !== Thread.currentThread()
        ) {
            try {
                receiverThread.join(1200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        thread = null
    }


    private fun runServer() {

        try {

            serverSocket =
                ServerSocket().apply {

                    reuseAddress = true

                    bind(
                        InetSocketAddress(
                            port
                        )
                    )
                }

            while (running) {

                statusCallback("WAITING")

                var audioTrack: AudioTrack? = null

                try {

                    val socket =
                        serverSocket
                            ?.accept()
                            ?: break

                    if (!running) {
                        closeQuietly(socket)
                        break
                    }

                    clientSocket = socket

                    socket.tcpNoDelay = true
                    socket.keepAlive = true

                    // Limit queued network audio so a temporary playback
                    // stall does not turn into seconds of growing delay.
                    socket.receiveBufferSize =
                        16 * 1024

                    val channelMask =
                        AudioFormat.CHANNEL_OUT_STEREO

                    val encoding =
                        AudioFormat.ENCODING_PCM_16BIT

                    val minimum =
                        AudioTrack.getMinBufferSize(
                            SAMPLE_RATE,
                            channelMask,
                            encoding
                        )

                    val requestedBuffer =
                        max(
                            minimum,
                            READ_BUFFER_BYTES
                        )

                    // AudioTrack buffers should end on a complete
                    // stereo PCM frame.
                    val bufferSize =
                        (
                            (
                                requestedBuffer +
                                    FRAME_BYTES - 1
                            ) /
                                FRAME_BYTES
                        ) *
                            FRAME_BYTES

                    audioTrack =
                        createAudioTrack(
                            SAMPLE_RATE,
                            channelMask,
                            encoding,
                            bufferSize
                        )

                    audioTrack.play()

                    statusCallback("RECEIVING")

                    val input =
                        socket.getInputStream()

                    val buffer =
                        ByteArray(
                            READ_BUFFER_BYTES
                        )

                    while (running) {

                        val count =
                            input.read(
                                buffer
                            )

                        if (count <= 0) {
                            break
                        }

                        // Non-blocking playback deliberately favors
                        // "live" audio over building a long queue.
                        // Under a brief TV stall, unwritten tail bytes
                        // are dropped rather than accumulating latency.
                        var offset = 0

                        while (
                            offset < count &&
                            running
                        ) {

                            val written =
                                audioTrack.write(
                                    buffer,
                                    offset,
                                    count - offset,
                                    AudioTrack.WRITE_NON_BLOCKING
                                )

                            if (written > 0) {
                                offset += written
                            } else {
                                break
                            }
                        }
                    }

                } catch (e: Exception) {

                    if (running) {
                        e.printStackTrace()
                    }

                } finally {

                    releaseAudioTrack(
                        audioTrack
                    )

                    closeQuietly(
                        clientSocket
                    )

                    clientSocket = null

                    if (running) {
                        statusCallback("WAITING")
                    }
                }
            }

        } catch (e: Exception) {

            if (running) {
                e.printStackTrace()
            }

        } finally {

            closeQuietly(
                serverSocket
            )

            serverSocket = null

            if (running) {
                statusCallback("WAITING")
            }
        }
    }


    private fun releaseAudioTrack(
        audioTrack: AudioTrack?
    ) {

        if (audioTrack == null) {
            return
        }

        try {
            audioTrack.pause()
        } catch (_: Exception) {
        }

        try {
            audioTrack.flush()
        } catch (_: Exception) {
        }

        try {
            audioTrack.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack.release()
        } catch (_: Exception) {
        }
    }


    @Suppress("DEPRECATION")
    private fun createAudioTrack(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSize: Int
    ): AudioTrack {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            try {

                return AudioTrack.Builder()

                    .setAudioAttributes(
                        AudioAttributes.Builder()

                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )

                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_MOVIE
                            )

                            .build()
                    )

                    .setAudioFormat(
                        AudioFormat.Builder()

                            .setEncoding(
                                encoding
                            )

                            .setSampleRate(
                                sampleRate
                            )

                            .setChannelMask(
                                channelMask
                            )

                            .build()
                    )

                    .setBufferSizeInBytes(
                        bufferSize
                    )

                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )

                    .setPerformanceMode(
                        AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                    )

                    .build()

            } catch (_: Exception) {

                // Some Android TV devices expose the API but reject
                // the low-latency performance mode. Fall back cleanly.
                return AudioTrack.Builder()

                    .setAudioAttributes(
                        AudioAttributes.Builder()

                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )

                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_MOVIE
                            )

                            .build()
                    )

                    .setAudioFormat(
                        AudioFormat.Builder()

                            .setEncoding(
                                encoding
                            )

                            .setSampleRate(
                                sampleRate
                            )

                            .setChannelMask(
                                channelMask
                            )

                            .build()
                    )

                    .setBufferSizeInBytes(
                        bufferSize
                    )

                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )

                    .build()
            }
        }

        return AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelMask,
            encoding,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
    }


    private fun closeQuietly(
        socket: Socket?
    ) {

        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }


    private fun closeQuietly(
        socket: ServerSocket?
    ) {

        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
