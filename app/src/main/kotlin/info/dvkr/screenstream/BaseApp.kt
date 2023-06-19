package info.dvkr.screenstream

import android.app.Application
import com.elvishew.xlog.flattener.ClassicFlattener
import com.elvishew.xlog.printer.file.FilePrinter
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy
import info.dvkr.screenstream.di.baseKoinModule
import info.dvkr.screenstream.logging.DateSuffixFileNameGenerator
import info.dvkr.screenstream.logging.getLogFolder
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

abstract class BaseApp : Application() {



    protected val filePrinter: FilePrinter by lazy {
        FilePrinter.Builder(getLogFolder())
            .fileNameGenerator(DateSuffixFileNameGenerator(this@BaseApp.hashCode().toString()))
            .cleanStrategy(FileLastModifiedCleanStrategy(86400000)) // One day
            .flattener(ClassicFlattener())
            .build()
    }

    val lastAdLoadTimeMap: MutableMap<String, Long> = mutableMapOf()

    override fun onCreate() {
        super.onCreate()

        initLogger()

        // Cấu hình HTTPS và SSL
//        try {
//            val certificateFactory = CertificateFactory.getInstance("X.509")
//            val certificateInputStream = assets.open("certificate.crt")
//            val certificate: X509Certificate = certificateFactory.generateCertificate(certificateInputStream) as X509Certificate
//
//
//            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
//            keyStore.load(null, null)
//            keyStore.setCertificateEntry("HN", certificate)
//
//            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
//            trustManagerFactory.init(keyStore)
//
//            val sslContext = SSLContext.getInstance("TLS")
//
//            // Thay đổi dòng này để truyền password vào keystore
//            val password = "thjnhotwp1".toCharArray()
//            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
//            keyManagerFactory.init(keyStore, password)
//
//            sslContext.init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)
//
//            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@BaseApp)
            modules(baseKoinModule)
        }

    }


    abstract fun initLogger()

//    override fun onCreate() {
//        super.onCreate()
//
//        initLogger()
//
//        startKoin {
//            androidLogger(Level.ERROR)
//            androidContext(this@BaseApp)
//            modules(baseKoinModule)
//        }
//
//    }

    internal val sharedPreferences by lazy(LazyThreadSafetyMode.NONE) {
        getSharedPreferences("logging.xml", MODE_PRIVATE)
    }

    internal var isLoggingOn: Boolean
        get() = sharedPreferences.getBoolean(LOGGING_ON_KEY, false)
        set(value) {
            sharedPreferences.edit().putBoolean(LOGGING_ON_KEY, value).commit()
        }

    internal companion object {
        const val LOGGING_ON_KEY = "loggingOn"
    }
}