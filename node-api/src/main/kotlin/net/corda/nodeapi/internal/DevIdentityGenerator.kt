package net.corda.nodeapi.internal

import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.utilities.trace
import net.corda.nodeapi.internal.config.FileBasedCertificateStoreSupplier
import net.corda.nodeapi.internal.config.SslConfiguration
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509KeyStore
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.X509Utilities.DISTRIBUTED_NOTARY_ALIAS_PREFIX
import net.corda.nodeapi.internal.crypto.X509Utilities.NODE_IDENTITY_ALIAS_PREFIX
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.KeyPair
import java.security.PublicKey

/**
 * Contains utility methods for generating identities for a node.
 *
 * WARNING: This is not application for production use.
 */
object DevIdentityGenerator {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Install a node key store for the given node directory using the given legal name. */
    fun installKeyStoreWithNodeIdentity(nodeDir: Path, legalName: CordaX500Name): Party {
        val certificatesDirectory = nodeDir / "certificates"
        val signingCertStore = FileBasedCertificateStoreSupplier(certificatesDirectory / "nodekeystore.jks", "cordacadevpass")
        val p2pKeyStore = FileBasedCertificateStoreSupplier(certificatesDirectory / "sslkeystore.jks", "cordacadevpass")
        val p2pTrustStore = FileBasedCertificateStoreSupplier(certificatesDirectory / "truststore.jks", "trustpass")
        val p2pSslConfig = SslConfiguration.mutual(p2pKeyStore, p2pTrustStore)

        certificatesDirectory.createDirectories()
        val nodeKeyStore = signingCertStore.get(true).also { it.registerDevSigningCertificates(legalName) }
        p2pSslConfig.keyStore.get(true).also { it.registerDevP2pCertificates(legalName) }

        val identity = nodeKeyStore.storeLegalIdentity("$NODE_IDENTITY_ALIAS_PREFIX-private-key")
        return identity.party
    }

    fun generateDistributedNotaryCompositeIdentity(dirs: List<Path>, notaryName: CordaX500Name, threshold: Int = 1): Party {
        require(dirs.isNotEmpty())

        log.trace { "Generating composite identity \"$notaryName\" for nodes: ${dirs.joinToString()}" }
        val keyPairs = (1..dirs.size).map { generateKeyPair() }
        val notaryKey = CompositeKey.Builder().addKeys(keyPairs.map { it.public }).build(threshold)
        keyPairs.zip(dirs) { keyPair, nodeDir ->
            generateCertificates(keyPair, notaryKey, notaryName, nodeDir)
        }
        return Party(notaryName, notaryKey)
    }

    fun generateDistributedNotarySingularIdentity(dirs: List<Path>, notaryName: CordaX500Name): Party {
        require(dirs.isNotEmpty())

        log.trace { "Generating singular identity \"$notaryName\" for nodes: ${dirs.joinToString()}" }
        val keyPair = generateKeyPair()
        val notaryKey = keyPair.public
        dirs.forEach { dir ->
            generateCertificates(keyPair, notaryKey, notaryName, dir)
        }
        return Party(notaryName, notaryKey)
    }

    private fun generateCertificates(keyPair: KeyPair, notaryKey: PublicKey, notaryName: CordaX500Name, nodeDir: Path) {
        val (serviceKeyCert, compositeKeyCert) = listOf(keyPair.public, notaryKey).map { publicKey ->
            X509Utilities.createCertificate(
                    CertificateType.SERVICE_IDENTITY,
                    DEV_INTERMEDIATE_CA.certificate,
                    DEV_INTERMEDIATE_CA.keyPair,
                    notaryName.x500Principal,
                    publicKey)
        }
        val distServKeyStoreFile = (nodeDir / "certificates").createDirectories() / "distributedService.jks"
        X509KeyStore.fromFile(distServKeyStoreFile, "cordacadevpass", createNew = true).update {
            setCertificate("$DISTRIBUTED_NOTARY_ALIAS_PREFIX-composite-key", compositeKeyCert)
            setPrivateKey(
                    "$DISTRIBUTED_NOTARY_ALIAS_PREFIX-private-key",
                    keyPair.private,
                    listOf(serviceKeyCert, DEV_INTERMEDIATE_CA.certificate, DEV_ROOT_CA.certificate),
                    "cordacadevkeypass")
        }
    }
}
