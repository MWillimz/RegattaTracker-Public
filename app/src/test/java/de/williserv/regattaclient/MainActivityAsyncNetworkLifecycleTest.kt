package de.williserv.regattaclient

import android.content.Context
import androidx.compose.runtime.MutableState
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.jvm.functions.Function0
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class MainActivityAsyncNetworkLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPrefs()
    }

    @After
    fun tearDown() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        clearPrefs()
    }

    @Test
    fun `event response after destroy cannot persist snapshot`() {
        BlockingHttpServer(EVENT_RESPONSE).use { server ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            configureAccess(activity, server.baseUrl)

            invokeNoArg(activity, "fetchRaceDataForDisplay")
            assertTrue(server.awaitFirstRequest())

            controller.destroy()
            server.releaseFirstResponse()
            assertTrue(server.awaitFirstResponseSent())
            settleAsyncWork()

            val prefs = context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
            assertFalse(prefs.getBoolean("race_data_ready", false))
            assertEquals("", prefs.getString("resolved_event_name", ""))
        }
    }

    @Test
    fun `compatibility completion after destroy starts no follow up requests`() {
        BlockingHttpServer("""{"server_build_id":"test"}""").use { server ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            configureAccess(activity, server.baseUrl)

            invokeNullableLong(activity, "fetchRaceLegalText", null)
            assertTrue(server.awaitFirstRequest())

            controller.destroy()
            server.releaseFirstResponse()
            assertTrue(server.awaitFirstResponseSent())
            settleAsyncWork(350)

            assertEquals(1, server.requestCount.get())
        }
    }

    @Test
    fun `legal get response after destroy cannot mutate legal or connection state`() {
        BlockingHttpServer(LEGAL_RESPONSE).use { server ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            val access = configureAccess(activity, server.baseUrl)
            val compatibility = allowCompatibility(activity, access)

            invokeLegalFetch(activity, compatibility)
            assertTrue(server.awaitFirstRequest())

            controller.destroy()
            server.releaseFirstResponse()
            assertTrue(server.awaitFirstResponseSent())
            settleAsyncWork()

            assertFalse(getState<Boolean>(activity, "raceLegalAccepted").value)
            assertEquals("", getState<String>(activity, "raceLegalHash").value)
            assertEquals(ServerConnectionState.UNKNOWN, ServerConnectionStateStore.state(context, access.server))
        }
    }

    @Test
    fun `legal accept response after destroy cannot accept or start follow ups`() {
        BlockingHttpServer("""{"event_name":"test-event"}""").use { server ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            val access = configureAccess(activity, server.baseUrl)
            val compatibility = allowCompatibility(activity, access)
            val document = EventLegalDocumentContext(
                compatibility = compatibility,
                resolvedEventName = "test-event",
                legalHash = "hash-1"
            )
            setField(activity, "raceLegalResolvedEventName", "test-event")
            getState<String>(activity, "raceLegalHash").value = "hash-1"
            getField<EventLegalFlowState>(activity, "eventLegalFlowState").display(document)

            invokeNoArg(activity, "acceptRaceLegalAndLoadRaceData")
            assertTrue(server.awaitFirstRequest())

            controller.destroy()
            server.releaseFirstResponse()
            assertTrue(server.awaitFirstResponseSent())
            settleAsyncWork(350)

            assertFalse(getState<Boolean>(activity, "raceLegalAccepted").value)
            assertEquals(1, server.requestCount.get())
            assertEquals(ServerConnectionState.UNKNOWN, ServerConnectionStateStore.state(context, access.server))
        }
    }

    @Test
    fun `registration response after destroy cannot register or call success callback`() {
        BlockingHttpServer("{}").use { server ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            val access = configureAccess(activity, server.baseUrl)
            getState<Boolean>(activity, "setupConfirmed").value = true
            getState<Boolean>(activity, "raceDataReady").value = true
            getState<Boolean>(activity, "raceLegalAccepted").value = true
            setField(activity, "rawRaceStart", "2026-09-16T10:00:00Z")
            val successCalled = AtomicBoolean(false)

            invokeRegistration(activity) { successCalled.set(true) }
            assertTrue(server.awaitFirstRequest())

            controller.destroy()
            server.releaseFirstResponse()
            assertTrue(server.awaitFirstResponseSent())
            settleAsyncWork()

            assertFalse(getState<Boolean>(activity, "raceRegistered").value)
            assertFalse(successCalled.get())
            assertEquals(ServerConnectionState.UNKNOWN, ServerConnectionStateStore.state(context, access.server))
        }
    }

    @Test
    fun `results response after destroy cannot mutate results state`() {
        BlockingHttpServer(RESULTS_RESPONSE).use { server ->
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            val activity = controller.get()
            configureAccess(activity, server.baseUrl)

            invokeNoArg(activity, "fetchEventResults")
            assertTrue(server.awaitFirstRequest())

            controller.destroy()
            server.releaseFirstResponse()
            assertTrue(server.awaitFirstResponseSent())
            settleAsyncWork()

            assertFalse(getState<Boolean>(activity, "resultsPublished").value)
            assertTrue(getState<List<ResultRow>>(activity, "resultRows").value.isEmpty())
        }
    }

    @Test
    fun `new activity remains independent after old activity is destroyed`() {
        val oldController = Robolectric.buildActivity(MainActivity::class.java).create()
        val oldActivity = oldController.get()
        oldController.destroy()

        val newController = Robolectric.buildActivity(MainActivity::class.java).create()
        val newActivity = newController.get()

        assertFalse(getField<ActivityAsyncLifetime>(oldActivity, "asyncLifetime").isActive())
        assertTrue(getField<ActivityAsyncLifetime>(newActivity, "asyncLifetime").isActive())
        assertNull(invokeCurrentAccess(newActivity))

        newController.destroy()
    }

    private fun configureAccess(activity: MainActivity, server: String): EventAccessKey {
        getState<String>(activity, "raceServer").value = server
        getState<String>(activity, "raceEvent").value = "test-event"
        getState<String>(activity, "raceSecret").value = "test-secret"
        return EventAccessKey(server, "test-event", "test-secret")
    }

    private fun allowCompatibility(activity: MainActivity, access: EventAccessKey): EventCompatibilityContext {
        val generation = getField<Long>(activity, "eventCompatibilityGeneration")
        setField(activity, "eventCompatibilityAllowedAccess", access)
        return EventCompatibilityContext(access, generation)
    }

    private fun invokeNoArg(target: Any, methodName: String) {
        target.javaClass.getDeclaredMethod(methodName).apply {
            isAccessible = true
            invoke(target)
        }
    }

    private fun invokeNullableLong(target: Any, methodName: String, value: Long?) {
        target.javaClass.getDeclaredMethod(methodName, java.lang.Long::class.java).apply {
            isAccessible = true
            invoke(target, value)
        }
    }

    private fun invokeLegalFetch(target: Any, compatibility: EventCompatibilityContext) {
        target.javaClass.getDeclaredMethod(
            "fetchRaceLegalTextAfterCompatibility",
            EventCompatibilityContext::class.java,
            java.lang.Long::class.java
        ).apply {
            isAccessible = true
            invoke(target, compatibility, null)
        }
    }

    private fun invokeRegistration(target: Any, onSuccess: () -> Unit) {
        target.javaClass.getDeclaredMethod("registerForRace", Function0::class.java).apply {
            isAccessible = true
            val callback: () -> Unit = { onSuccess() }
            invoke(target, callback)
        }
    }

    private fun invokeCurrentAccess(target: Any): EventAccessKey? =
        target.javaClass.getDeclaredMethod("currentEventAccessKey").let { method ->
            method.isAccessible = true
            method.invoke(target) as EventAccessKey?
        }

    private fun settleAsyncWork(delayMillis: Long = 200L) {
        Thread.sleep(delayMillis)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(target: Any, fieldName: String): T =
        target.javaClass.getDeclaredField(fieldName).let { field ->
            field.isAccessible = true
            field.get(target) as T
        }

    private fun <T> getState(target: Any, fieldName: String): MutableState<T> =
        getField(target, fieldName)

    private fun clearPrefs() {
        listOf(
            "app_state",
            "boat_setup",
            "race_setup",
            "regatta_race_state",
            "regatta_local_status",
            "regatta_connection_state"
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private class BlockingHttpServer(
        private val responseBody: String,
        private val statusCode: Int = 200
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val firstRequestReceived = CountDownLatch(1)
        private val releaseResponse = CountDownLatch(1)
        private val firstResponseSent = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        val requestCount = AtomicInteger(0)
        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

        private val acceptThread = thread(start = true, isDaemon = true, name = "test-http-server") {
            while (!closed.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                handle(socket)
            }
        }

        private fun handle(socket: Socket) {
            socket.use { connection ->
                readHeaders(connection)
                val requestNumber = requestCount.incrementAndGet()
                if (requestNumber == 1) {
                    firstRequestReceived.countDown()
                    releaseResponse.await(5, TimeUnit.SECONDS)
                }
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
                if (requestNumber == 1) firstResponseSent.countDown()
            }
        }

        private fun readHeaders(socket: Socket) {
            val input = BufferedInputStream(socket.getInputStream())
            var state = 0
            while (state < 4) {
                val b = input.read()
                if (b < 0) return
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
        }

        fun awaitFirstRequest(): Boolean = firstRequestReceived.await(3, TimeUnit.SECONDS)

        fun releaseFirstResponse() {
            releaseResponse.countDown()
        }

        fun awaitFirstResponseSent(): Boolean = firstResponseSent.await(3, TimeUnit.SECONDS)

        override fun close() {
            releaseResponse.countDown()
            closed.set(true)
            runCatching { serverSocket.close() }
            acceptThread.join(1000)
        }
    }

    private companion object {
        const val EVENT_RESPONSE = """{
          "event_name":"test-event",
          "race_status":"scheduled",
          "start_time":"2026-09-16T10:00:00Z",
          "stop_time":"2026-09-16T14:00:00Z",
          "course":{"marks":[]}
        }"""

        const val LEGAL_RESPONSE = """{
          "event_name":"test-event",
          "invitation_legal_text":"Test legal text",
          "legal_text_hash":"hash-1",
          "legal_text_version":"1"
        }"""

        const val RESULTS_RESPONSE = """{
          "published":true,
          "published_at":"2026-09-16T14:30:00Z",
          "rows":[{
            "rank":1,
            "boat_name":"Test Boat",
            "sail_number":"42",
            "status":"finished",
            "official_finish_time":"2026-09-16T12:00:00Z",
            "corrected_time":"01:55:00"
          }]
        }"""
    }
}
