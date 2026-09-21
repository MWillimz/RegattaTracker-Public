package de.williserv.regattaclient

import android.content.Context
import androidx.compose.runtime.MutableState
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParticipantRetirementNetworkTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPrefs()
        ParticipantRetirementStore.clearForTests(context)
    }

    @After
    fun tearDown() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        clearPrefs()
        ParticipantRetirementStore.clearForTests(context)
    }

    @Test
    fun `successful retire reports stable credentials and keeps tracking active`() {
        RetirementHttpServer(200).use { server ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            configureRunningRace(activity, server.baseUrl)

            invokeNoArg(activity, "reportRetirement")
            assertTrue(server.awaitRequest())
            server.releaseResponse()
            assertTrue(server.awaitResponseSent())
            settleAsyncWork(activity)

            assertEquals("/event/participant/retire", server.path)
            assertEquals("test-secret", server.headers["x-shared-secret"])
            assertEquals(RegattaTrackingService.API_VERSION, server.headers["x-api-version"])

            val json = JSONObject(server.body)
            assertEquals("series-access", json.getString("event_name"))
            assertEquals("GER 147", json.getString("sail_number"))
            assertEquals("Test Boat", json.getString("boat_name"))
            assertEquals("Test Skipper", json.getString("captain_name"))

            assertTrue(getState<Boolean>(activity, "inRace").value)
            assertTrue(getState<Boolean>(activity, "retirementReported").value)
            assertTrue(
                ParticipantRetirementStore.matches(
                    context,
                    "Series Race 3",
                    ParticipantRetirementIdentity("GER 147", "Test Boat", "Test Skipper")
                )
            )

            controller.destroy()
        }
    }

    @Test
    fun `failed retire keeps race active and stores no self report`() {
        RetirementHttpServer(500, """{"detail":"boom"}""").use { server ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            configureRunningRace(activity, server.baseUrl)

            invokeNoArg(activity, "reportRetirement")
            assertTrue(server.awaitRequest())
            server.releaseResponse()
            assertTrue(server.awaitResponseSent())
            settleAsyncWork(activity)

            assertTrue(getState<Boolean>(activity, "inRace").value)
            assertFalse(getState<Boolean>(activity, "retirementReported").value)
            assertTrue(getState<String>(activity, "retirementStatusText").value.isNotBlank())

            controller.destroy()
        }
    }

    private fun configureRunningRace(activity: MainActivity, server: String) {
        getState<String>(activity, "raceServer").value = server
        getState<String>(activity, "raceEvent").value = "series-access"
        getState<String>(activity, "raceSecret").value = "test-secret"
        getState<String>(activity, "resolvedEventName").value = "Series Race 3"
        getState<String>(activity, "sailNumber").value = "GER 147"
        getState<String>(activity, "boatName").value = "Test Boat"
        getState<String>(activity, "skipperName").value = "Test Skipper"
        getState<Boolean>(activity, "inRace").value = true
        getState<Boolean>(activity, "setupConfirmed").value = true
    }

    private fun invokeNoArg(target: Any, methodName: String) {
        target.javaClass.getDeclaredMethod(methodName).apply {
            isAccessible = true
            invoke(target)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getState(target: Any, fieldName: String): MutableState<T> =
        target.javaClass.getDeclaredField(fieldName).let { field ->
            field.isAccessible = true
            field.get(target) as MutableState<T>
        }

    private fun settleAsyncWork(
        activity: MainActivity,
        timeoutMillis: Long = 3_000L
    ) {
        val deadlineNanos =
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

        while (System.nanoTime() < deadlineNanos) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (!getState<Boolean>(activity, "retirementRequestInFlight").value) {
                return
            }
            Thread.sleep(10L)
        }

        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(getState<Boolean>(activity, "retirementRequestInFlight").value)
    }

    private fun clearPrefs() {
        listOf(
            "app_state",
            "boat_setup",
            "race_setup",
            "regatta_race_state",
            "regatta_local_status",
            "regatta_connection_state"
        ).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private class RetirementHttpServer(
        private val statusCode: Int,
        private val responseBody: String =
            """{"status":"ret","event_name":"Series Race 3","sail_number":"GER 147","boat_name":"Test Boat","captain_name":"Test Skipper","reported_at":"2026-09-20T11:30:00+00:00"}"""
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val requestReceived = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val responseSent = CountDownLatch(1)

        @Volatile
        var path: String = ""

        @Volatile
        var headers: Map<String, String> = emptyMap()

        @Volatile
        var body: String = ""

        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

        private val acceptThread = thread(start = true, isDaemon = true) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                return@thread
            }
            handle(socket)
        }

        private fun handle(socket: Socket) {
            socket.use { connection ->
                val input = BufferedInputStream(connection.getInputStream())
                path = readLine(input).split(" ").getOrNull(1).orEmpty()

                val parsedHeaders = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        parsedHeaders[line.substring(0, separator).lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }
                headers = parsedHeaders

                val length = parsedHeaders["content-length"]?.toIntOrNull() ?: 0
                val payload = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(payload, offset, length - offset)
                    if (read < 0) break
                    offset += read
                }
                body = payload.copyOf(offset).toString(Charsets.UTF_8)
                requestReceived.countDown()

                release.await(5, TimeUnit.SECONDS)
                val bytes = responseBody.toByteArray(Charsets.UTF_8)
                val reason = if (statusCode in 200..299) "OK" else "Error"
                connection.getOutputStream().buffered().use { out ->
                    out.write("HTTP/1.1 $statusCode $reason\r\n".toByteArray())
                    out.write("Content-Type: application/json\r\n".toByteArray())
                    out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                    out.write("Connection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
                responseSent.countDown()
            }
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = input.read()
                if (value < 0 || value == '\n'.code) break
                if (value != '\r'.code) bytes.add(value.toByte())
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }

        fun awaitRequest(): Boolean = requestReceived.await(3, TimeUnit.SECONDS)

        fun releaseResponse() {
            release.countDown()
        }

        fun awaitResponseSent(): Boolean = responseSent.await(3, TimeUnit.SECONDS)

        override fun close() {
            release.countDown()
            runCatching { serverSocket.close() }
            acceptThread.join(1000)
        }
    }
}
