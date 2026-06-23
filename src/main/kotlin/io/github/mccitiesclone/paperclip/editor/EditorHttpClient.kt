package io.github.mccitiesclone.paperclip.editor

import io.github.mccitiesclone.paperclip.EditorSettings
import java.net.Socket
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.logging.Logger
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

internal fun editorHttpClient(
    settings: EditorSettings,
    dataFolder: Path,
    logger: Logger,
): HttpClient {
    val builder = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))

    if (settings.trustedCaCertificates.isNotEmpty()) {
        builder.sslContext(editorSslContext(settings.trustedCaCertificates, dataFolder, logger))
    }

    return builder.build()
}

private fun editorSslContext(
    certificatePaths: List<String>,
    dataFolder: Path,
    logger: Logger,
): SSLContext {
    val customTrustManager = customTrustManager(certificatePaths, dataFolder, logger)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(CompositeTrustManager(defaultTrustManager(), customTrustManager)), null)
    return sslContext
}

private fun customTrustManager(
    certificatePaths: List<String>,
    dataFolder: Path,
    logger: Logger,
): X509TrustManager {
    val certificateFactory = CertificateFactory.getInstance("X.509")
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    var certificateCount = 0

    certificatePaths.forEach { configuredPath ->
        val path = dataFolder.resolve(configuredPath).normalize()
            .takeUnless { configuredPath.startsWith("/") }
            ?: Path.of(configuredPath).normalize()
        if (!Files.isRegularFile(path)) {
            throw IllegalStateException("editor.trusted-ca-certificates file does not exist: $path")
        }

        Files.newInputStream(path).use { input ->
            certificateFactory.generateCertificates(input).forEach { certificate ->
                val x509 = certificate as? X509Certificate
                    ?: throw IllegalStateException("editor.trusted-ca-certificates contains a non-X509 certificate: $path")
                keyStore.setCertificateEntry("paperclip-editor-ca-${certificateCount++}", x509)
            }
        }
    }

    if (certificateCount == 0) {
        throw IllegalStateException("editor.trusted-ca-certificates did not load any certificates")
    }

    logger.info("Loaded $certificateCount editor TLS CA certificate(s).")
    return trustManagerFrom(keyStore)
}

private fun defaultTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.x509TrustManager()
}

private fun trustManagerFrom(keyStore: KeyStore): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore)
    return factory.x509TrustManager()
}

private fun TrustManagerFactory.x509TrustManager(): X509TrustManager =
    trustManagers
        .filterIsInstance<X509TrustManager>()
        .firstOrNull()
        ?: throw IllegalStateException("No X509 trust manager is available")

private class CompositeTrustManager(
    private val defaultTrustManager: X509TrustManager,
    private val customTrustManager: X509TrustManager,
) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        check(chain, authType) { manager, checkedChain, checkedAuthType ->
            manager.checkClientTrusted(checkedChain, checkedAuthType)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        check(chain, authType) { manager, checkedChain, checkedAuthType ->
            manager.checkServerTrusted(checkedChain, checkedAuthType)
        }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
        check(chain, authType) { manager, checkedChain, checkedAuthType ->
            if (manager is X509ExtendedTrustManager) {
                manager.checkClientTrusted(checkedChain, checkedAuthType, socket)
            } else {
                manager.checkClientTrusted(checkedChain, checkedAuthType)
            }
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
        check(chain, authType) { manager, checkedChain, checkedAuthType ->
            if (manager is X509ExtendedTrustManager) {
                manager.checkServerTrusted(checkedChain, checkedAuthType, socket)
            } else {
                manager.checkServerTrusted(checkedChain, checkedAuthType)
            }
        }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
        check(chain, authType) { manager, checkedChain, checkedAuthType ->
            if (manager is X509ExtendedTrustManager) {
                manager.checkClientTrusted(checkedChain, checkedAuthType, engine)
            } else {
                manager.checkClientTrusted(checkedChain, checkedAuthType)
            }
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
        check(chain, authType) { manager, checkedChain, checkedAuthType ->
            if (manager is X509ExtendedTrustManager) {
                manager.checkServerTrusted(checkedChain, checkedAuthType, engine)
            } else {
                manager.checkServerTrusted(checkedChain, checkedAuthType)
            }
        }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        defaultTrustManager.acceptedIssuers + customTrustManager.acceptedIssuers

    private fun check(
        chain: Array<X509Certificate>,
        authType: String,
        verifier: (X509TrustManager, Array<X509Certificate>, String) -> Unit,
    ) {
        runCatching {
            verifier(defaultTrustManager, chain, authType)
        }.getOrElse { defaultFailure ->
            runCatching {
                verifier(customTrustManager, chain, authType)
            }.getOrElse {
                throw defaultFailure
            }
        }
    }
}
