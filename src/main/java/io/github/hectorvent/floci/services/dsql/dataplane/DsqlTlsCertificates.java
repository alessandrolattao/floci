package io.github.hectorvent.floci.services.dsql.dataplane;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The certificate every DSQL endpoint presents: one leaf, signed by Floci's root CA, naming
 * {@code *.dsql.<region>.<endpoint-suffix>} for every Region Floci knows, so a client verifying
 * the endpoint's name (sslmode=verify-full, as DSQL's own connectors do) only has to trust
 * Floci's CA, the one every launched container already trusts.
 */
@ApplicationScoped
public class DsqlTlsCertificates {

    private final FlociCertificateAuthority certificateAuthority;
    private final String endpointSuffix;
    private volatile SSLContext context;

    @Inject
    public DsqlTlsCertificates(FlociCertificateAuthority certificateAuthority, EmulatorConfig config) {
        this(certificateAuthority, config.services().dsql().endpointSuffix());
    }

    DsqlTlsCertificates(FlociCertificateAuthority certificateAuthority, String endpointSuffix) {
        this.certificateAuthority = certificateAuthority;
        this.endpointSuffix = endpointSuffix;
    }

    public SSLContext sslContext() {
        SSLContext current = context;
        if (current == null) {
            synchronized (this) {
                if (context == null) {
                    context = build();
                }
                current = context;
            }
        }
        return current;
    }

    private SSLContext build() {
        try {
            List<String> sans = new ArrayList<>();
            for (String region : new TreeSet<>(AwsRegions.KNOWN_IDS)) {
                sans.add("*.dsql." + region + "." + endpointSuffix);
            }
            CertificateGenerator.GeneratedCertificate generated = certificateAuthority.issueServerCertificate(
                    "dsql." + endpointSuffix, sans, KeyAlgorithm.RSA_2048, null);
            X509Certificate certificate = Pem.parseCertificate(generated.certificatePem());
            PrivateKey key = Pem.parsePrivateKey(generated.privateKeyPem());
            char[] password = new char[0];
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            keyStore.setKeyEntry("floci-dsql", key, password,
                    new Certificate[]{certificate, certificateAuthority.certificate()});
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, password);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(keyManagers.getKeyManagers(), null, new SecureRandom());
            return ssl;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue the DSQL endpoint certificate", e);
        }
    }
}
