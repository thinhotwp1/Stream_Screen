package info.dvkr.screenstream.mjpeg.httpserver

import io.ktor.server.http.content.staticBasePackage

import android.content.Context
import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.*
import info.dvkr.screenstream.mjpeg.image.NotificationBitmap
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.Jetty
import io.ktor.server.jetty.JettyApplicationEngine
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.interfaces.RSAPrivateKey
import java.security.spec.KeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
internal class HttpServer(
    applicationContext: Context,
    private val parentCoroutineScope: CoroutineScope,
    private val mjpegSettings: MjpegSettings,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val notificationBitmap: NotificationBitmap
) {

    sealed class Event {

        sealed class Action : Event() {
            object StartStopRequest : Action()
        }

        sealed class Statistic : Event() {
            class Clients(val clients: List<MjpegClient>) : Statistic()
            class Traffic(val traffic: List<MjpegTrafficPoint>) : Statistic()
        }

        class Error(val error: AppError) : Event()

        override fun toString(): String = javaClass.simpleName
    }

    private val _eventSharedFlow = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val eventSharedFlow: SharedFlow<Event> = _eventSharedFlow.asSharedFlow()

    private val httpServerFiles: HttpServerFiles = HttpServerFiles(applicationContext, mjpegSettings)
    private val certificateInputStream = applicationContext.assets.open("certificate.crt")
    private val privateKeyInputStream = applicationContext.assets.open("private.pk8")
    private val clientData: ClientData = ClientData(mjpegSettings) { sendEvent(it) }
    private val stopDeferred: AtomicReference<CompletableDeferred<Unit>?> = AtomicReference(null)
    private lateinit var blockedJPEG: ByteArray

    private var ktorServer: NettyApplicationEngine? = null

    init {
        XLog.d(getLog("init"))
    }

    fun start(serverAddresses: List<NetInterface>) {
        XLog.d(getLog("startServer"))

        val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            if (throwable is IOException && throwable !is BindException) return@CoroutineExceptionHandler
            if (throwable is CancellationException) return@CoroutineExceptionHandler
            XLog.d(getLog("onCoroutineException", "ktorServer: ${ktorServer?.hashCode()}: $throwable"))
            XLog.e(getLog("onCoroutineException", throwable.toString()), throwable)
            ktorServer?.stop(0, 250)
            ktorServer = null
            when (throwable) {
                is BindException -> sendEvent(Event.Error(AddressInUseException))
                else -> sendEvent(Event.Error(HttpServerException))
            }
        }
        val coroutineScope = CoroutineScope(Job() + Dispatchers.Default + coroutineExceptionHandler)

        runBlocking(coroutineScope.coroutineContext) {
            blockedJPEG = ByteArrayOutputStream().apply {
                notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.ADDRESS_BLOCKED)
                    .compress(Bitmap.CompressFormat.JPEG, 100, this)
            }.toByteArray()
        }

        val resultJpegStream = ByteArrayOutputStream()
        val lastJPEG: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))

        val mjpegSharedFlow = bitmapStateFlow
            .map { bitmap ->
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, mjpegSettings.jpegQualityFlow.first(), resultJpegStream)
                resultJpegStream.toByteArray()
            }
            .flatMapLatest { jpeg ->
                lastJPEG.set(jpeg)
                flow<ByteArray> { // Send last image every second as keep-alive //TODO Add settings option for this
                    while (currentCoroutineContext().isActive) {
                        emit(jpeg)
                        delay(1000)
                    }
                }
            }
            .conflate()
            .shareIn(coroutineScope, SharingStarted.Eagerly, 1)

        runBlocking(coroutineScope.coroutineContext) {
            httpServerFiles.configure()
            clientData.configure()
        }

        //
//        val keystorePassword = "123456".toCharArray()
//        val keyAlias = "123456"
//        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
//        keyStore.load(null)
//
//        // Load chứng chỉ từ tệp certificate.crt và khóa riêng tư từ tệp private.key
//        val certificateBytes = certificateInputStream.readBytes()
//        val privateKeyBytes = privateKeyInputStream.readBytes()
//
//        // Đặt chứng chỉ vào keystore
//        keyStore.setCertificateEntry(keyAlias, certificateBytes.toCertificate())
//
//        // Đặt khóa riêng tư vào keystore
//        keyStore.setKeyEntry(keyAlias, privateKeyBytes.toPrivateKey(), keystorePassword, arrayOf(certificateBytes.toCertificate()))
//
//        val environment = applicationEngineEnvironment {
//            parentCoroutineContext = coroutineScope.coroutineContext
//            watchPaths = emptyList() // Fix for java.lang.ClassNotFoundException: java.nio.file.FileSystems for API < 26
//            module {
//                appModule(httpServerFiles, clientData, mjpegSharedFlow, lastJPEG, blockedJPEG, stopDeferred) { sendEvent(it) }
//            }
//            serverAddresses.forEach { netInterface ->
//                sslConnector(keyStore, keyAlias, { keystorePassword }, { keystorePassword }) {
//                    // Cấu hình các thuộc tính của kết nối SSL
//                    host = netInterface.address.hostAddress!!
//                    keyStorePath = null // Không sử dụng đường dẫn đến keystore file
//                    trustStore = null // Không sử dụng trust store
//                    port = 443 // Cổng HTTPS
////                    port = runBlocking(parentCoroutineContext) { mjpegSettings.serverPortFlow.first() }
//                    enabledProtocols = listOf("TLSv1.2") // Các giao thức được hỗ trợ
//                }
//            }
//        }
//        ktorServer = embeddedServer(CIO, environment) {
//            connectionIdleTimeoutSeconds = 10
//        }

        //2

        val keystorePassword = "123456".toCharArray()
        val keyAlias = "123456"
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)

        // Load chứng chỉ từ tệp certificate.crt và khóa riêng tư từ tệp private.key
        val certificateBytes = certificateInputStream.readBytes()
        val privateKeyBytes = privateKeyInputStream.readBytes()

        // Đặt chứng chỉ vào keystore
        keyStore.setCertificateEntry(keyAlias, certificateBytes.toCertificate())

        // Đặt khóa riêng tư vào keystore
        keyStore.setKeyEntry(keyAlias, privateKeyBytes.toPrivateKey(), keystorePassword, arrayOf(certificateBytes.toCertificate()))

        val environment = applicationEngineEnvironment {
            parentCoroutineContext = coroutineScope.coroutineContext
            watchPaths = emptyList() // Fix for java.lang.ClassNotFoundException: java.nio.file.FileSystems for API < 26
            module {
                appModule(httpServerFiles, clientData, mjpegSharedFlow, lastJPEG, blockedJPEG, stopDeferred) { sendEvent(it) }
            }
            serverAddresses.forEach { netInterface ->
                sslConnector(keyStore, keyAlias, { keystorePassword }, { keystorePassword }) {
                    // Cấu hình các thuộc tính của kết nối SSL
                    host = netInterface.address.hostAddress!!
                    keyStorePath = null // Không sử dụng đường dẫn đến keystore file
                    trustStore = null // Không sử dụng trust store
                    port = 443 // Cổng HTTPS
//                    port = runBlocking(parentCoroutineContext) { mjpegSettings.serverPortFlow.first() }
                    enabledProtocols = listOf("TLSv1.2") // Các giao thức được hỗ trợ
                }

            }
        }

        ktorServer = embeddedServer(Netty, environment)


        //3




        //3

//        val environment = applicationEngineEnvironment {
//            parentCoroutineContext = coroutineScope.coroutineContext
//            watchPaths = emptyList() // Fix for java.lang.ClassNotFoundException: java.nio.file.FileSystems for API < 26
//            module { appModule(httpServerFiles, clientData, mjpegSharedFlow, lastJPEG, blockedJPEG, stopDeferred) { sendEvent(it) } }
//            serverAddresses.forEach { netInterface ->
//                connector {
//                    host = netInterface.address.hostAddress!!
//                    port = runBlocking(parentCoroutineContext) { mjpegSettings.serverPortFlow.first() }
//                }
//            }
//        }




        var exception: AppError? = null
        try {
            ktorServer?.start(false)
        } catch (ignore: CancellationException) {
        } catch (ex: BindException) {
            XLog.w(getLog("startServer", ex.toString()))
            exception = AddressInUseException
        } catch (throwable: Throwable) {
            XLog.e(getLog("startServer >>>"))
            XLog.e(getLog("startServer"), throwable)
            exception = HttpServerException
        } finally {
            exception?.let {
                sendEvent(Event.Error(it))
                ktorServer?.stop(0, 250)
                ktorServer = null
            }
        }
    }

    fun ByteArray.toCertificate(): java.security.cert.Certificate {
        val certificateFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        println("certPathEncodings: ${certificateFactory.certPathEncodings}")
        return certificateFactory.generateCertificate(this.inputStream())
    }

    fun ByteArray.toPrivateKey(): PrivateKey {
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(this))
        return privateKey
    }

    fun stop(): CompletableDeferred<Unit> {
        XLog.d(getLog("stopServer"))

        return CompletableDeferred<Unit>().apply Deferred@{
            ktorServer?.apply {
                stopDeferred.set(this@Deferred)
                stop(0, 250)
                XLog.d(this@HttpServer.getLog("stopServer", "Deferred: ktorServer: ${ktorServer.hashCode()}"))
                ktorServer = null
            } ?: complete(Unit)
            XLog.d(this@HttpServer.getLog("stopServer", "Deferred"))
        }
    }

    fun destroy(): CompletableDeferred<Unit> {
        XLog.d(getLog("destroy"))
        clientData.destroy()
        return stop()
    }

    private fun sendEvent(event: Event) {
        parentCoroutineScope.launch { _eventSharedFlow.emit(event) }
    }
}