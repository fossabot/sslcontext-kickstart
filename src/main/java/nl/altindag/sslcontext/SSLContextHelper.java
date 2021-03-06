package nl.altindag.sslcontext;

import static java.util.Objects.isNull;
import static org.apache.commons.lang3.ArrayUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Optional;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.NoopHostnameVerifier;

import nl.altindag.sslcontext.exception.GenericKeyStoreException;
import nl.altindag.sslcontext.exception.GenericSSLContextException;
import nl.altindag.sslcontext.trustmanager.CompositeX509TrustManager;
import nl.altindag.sslcontext.trustmanager.TrustManagerFactoryWrapper;
import nl.altindag.sslcontext.trustmanager.UnsafeTrustManager;
import nl.altindag.sslcontext.util.KeystoreUtils;
import nl.altindag.sslcontext.util.TrustManagerUtils;

public class SSLContextHelper {

    private KeyStore identity;
    private char[] identityPassword;
    private KeyStore trustStore;
    private char[] trustStorePassword;

    private boolean securityEnabled;
    private boolean oneWayAuthenticationEnabled;
    private boolean twoWayAuthenticationEnabled;
    private boolean includeDefaultJdkTrustStore;
    private boolean trustingAllCertificatesWithoutValidationEnabled;

    private String protocol;
    private SSLContext sslContext;
    private CompositeX509TrustManager trustManager;
    private TrustManagerFactory trustManagerFactory;
    private KeyManagerFactory keyManagerFactory;
    private HostnameVerifier hostnameVerifier;

    private SSLContextHelper() {}

    private void createSSLContextWithTrustStore() {
        try {
            createSSLContext(null, createTrustManagerFactory().getTrustManagers());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new GenericSSLContextException(e);
        }
    }

    private void createSSLContextWithKeyStoreAndTrustStore() {
        try {
            createSSLContext(createKeyManagerFactory().getKeyManagers(),
                             createTrustManagerFactory().getTrustManagers());
        } catch (UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
            throw new GenericSSLContextException(e);
        }
    }

    private void createSSLContext(KeyManager[] keyManagers, TrustManager[] trustManagers) throws NoSuchAlgorithmException, KeyManagementException {
        sslContext = SSLContext.getInstance(protocol);
        sslContext.init(keyManagers, trustManagers , null);
    }

    private KeyManagerFactory createKeyManagerFactory() throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
        keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(identity, identityPassword);
        return keyManagerFactory;
    }

    private TrustManagerFactory createTrustManagerFactory() {
        CompositeX509TrustManager.Builder trustManagerBuilder = CompositeX509TrustManager.builder();

        if (trustingAllCertificatesWithoutValidationEnabled) {
            trustManagerBuilder.withTrustManager(UnsafeTrustManager.INSTANCE);
        }

        if (includeDefaultJdkTrustStore) {
            trustManagerBuilder.withTrustManager(TrustManagerUtils.createTrustManagerWithJdkTrustedCertificates());
        }

        if (isNull(trustStore)) {
            trustManager = trustManagerBuilder.build();
        } else {
            trustManager = trustManagerBuilder.withTrustStore(trustStore, TrustManagerFactory.getDefaultAlgorithm())
                                              .build();
        }
        trustManagerFactory = new TrustManagerFactoryWrapper(trustManager);
        return trustManagerFactory;
    }

    public KeyStore getIdentity() {
        return identity;
    }

    public char[] getIdentityPassword() {
        return identityPassword;
    }

    public KeyStore getTrustStore() {
        return trustStore;
    }

    public char[] getTrustStorePassword() {
        return trustStorePassword;
    }

    public boolean isSecurityEnabled() {
        return securityEnabled;
    }

    public boolean isOneWayAuthenticationEnabled() {
        return oneWayAuthenticationEnabled;
    }

    public boolean isTwoWayAuthenticationEnabled() {
        return twoWayAuthenticationEnabled;
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    public KeyManagerFactory getKeyManagerFactory() {
        return keyManagerFactory;
    }

    public X509TrustManager getX509TrustManager() {
        return trustManager;
    }

    public TrustManagerFactory getTrustManagerFactory() {
        return trustManagerFactory;
    }

    public X509Certificate[] getTrustedX509Certificate() {
        return Optional.ofNullable(trustManager)
                       .map(X509TrustManager::getAcceptedIssuers)
                       .orElse(new X509Certificate[]{});
    }

    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private static final String TRUST_STORE_VALIDATION_EXCEPTION_MESSAGE = "TrustStore details are empty, which are required to be present when SSL/TLS is enabled";
        private static final String TRUST_STRATEGY_VALIDATION_EXCEPTION_MESSAGE = "Trust strategy is missing. Please validate if the TrustStore is present, "
                + "or default including the JDK trustStore is enabled or "
                + "trusting all certificates without validation is enabled";

        private static final String IDENTITY_VALIDATION_EXCEPTION_MESSAGE = "Identity details are empty, which are required to be present when SSL/TLS is enabled";
        private static final String KEY_STORE_LOADING_EXCEPTION = "Failed to load the keystore";

        private String protocol = "TLSv1.2";
        private boolean hostnameVerifierEnabled = true;

        private KeyStore identity;
        private char[] identityPassword;
        private KeyStore trustStore;
        private char[] trustStorePassword;

        private boolean oneWayAuthenticationEnabled;
        private boolean twoWayAuthenticationEnabled;
        private boolean includeDefaultJdkTrustStore = false;
        private boolean trustingAllCertificatesWithoutValidationEnabled = false;

        public Builder withDefaultJdkTrustStore() {
            this.includeDefaultJdkTrustStore = true;
            this.oneWayAuthenticationEnabled = true;
            return this;
        }

        public Builder withTrustStore(String trustStorePath, char[] trustStorePassword) {
            return withTrustStore(trustStorePath, trustStorePassword, KeyStore.getDefaultType());
        }

        public Builder withTrustStore(String trustStorePath, char[] trustStorePassword, String trustStoreType) {
            if (isBlank(trustStorePath) || isEmpty(trustStorePassword)) {
                throw new GenericKeyStoreException(TRUST_STORE_VALIDATION_EXCEPTION_MESSAGE);
            }

            try {
                this.trustStore = KeystoreUtils.loadKeyStore(trustStorePath, trustStorePassword, trustStoreType);
                this.trustStorePassword = trustStorePassword;
            } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
                throw new GenericKeyStoreException(KEY_STORE_LOADING_EXCEPTION, e);
            }

            this.oneWayAuthenticationEnabled = true;
            return this;
        }

        public Builder withTrustStore(Path trustStorePath, char[] trustStorePassword) {
            return withTrustStore(trustStorePath, trustStorePassword, KeyStore.getDefaultType());
        }

        public Builder withTrustStore(Path trustStorePath, char[] trustStorePassword, String trustStoreType) {
            if (isNull(trustStorePath) || isEmpty(trustStorePassword) || isBlank(trustStoreType)) {
                throw new GenericKeyStoreException(TRUST_STORE_VALIDATION_EXCEPTION_MESSAGE);
            }

            try {
                this.trustStore = KeystoreUtils.loadKeyStore(trustStorePath, trustStorePassword, trustStoreType);
                this.trustStorePassword = trustStorePassword;
            } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
                throw new GenericKeyStoreException(KEY_STORE_LOADING_EXCEPTION, e);
            }

            this.oneWayAuthenticationEnabled = true;
            return this;
        }

        public Builder withTrustStore(KeyStore trustStore, char[] trustStorePassword) {
            validateKeyStore(trustStore, trustStorePassword, TRUST_STORE_VALIDATION_EXCEPTION_MESSAGE);
            this.trustStore = trustStore;
            this.trustStorePassword = trustStorePassword;
            this.oneWayAuthenticationEnabled = true;
            return this;
        }

        public Builder withIdentity(String identityPath, char[] identityPassword) {
            return withIdentity(identityPath, identityPassword, KeyStore.getDefaultType());
        }

        public Builder withIdentity(String identityPath, char[] identityPassword, String identityType) {
            if (isBlank(identityPath) || isEmpty(identityPassword) || isBlank(identityType)) {
                throw new GenericKeyStoreException(IDENTITY_VALIDATION_EXCEPTION_MESSAGE);
            }

            try {
                this.identity = KeystoreUtils.loadKeyStore(identityPath, identityPassword, identityType);
                this.identityPassword = identityPassword;
                this.twoWayAuthenticationEnabled = true;
            } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
                throw new GenericKeyStoreException(KEY_STORE_LOADING_EXCEPTION, e);
            }
            return this;
        }

        public Builder withIdentity(Path identityPath, char[] identityPassword) {
            return withIdentity(identityPath, identityPassword, KeyStore.getDefaultType());
        }

        public Builder withIdentity(Path identityPath, char[] identityPassword, String identityType) {
            if (isNull(identityPath) || isEmpty(identityPassword) || isBlank(identityType)) {
                throw new GenericKeyStoreException(IDENTITY_VALIDATION_EXCEPTION_MESSAGE);
            }

            try {
                this.identity = KeystoreUtils.loadKeyStore(identityPath, identityPassword, identityType);
                this.identityPassword = identityPassword;
                this.twoWayAuthenticationEnabled = true;
            } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
                throw new GenericKeyStoreException(KEY_STORE_LOADING_EXCEPTION, e);
            }
            return this;
        }

        public Builder withIdentity(KeyStore identity, char[] identityPassword) {
            validateKeyStore(identity, identityPassword, IDENTITY_VALIDATION_EXCEPTION_MESSAGE);
            this.identity = identity;
            this.identityPassword = identityPassword;
            this.twoWayAuthenticationEnabled = true;
            return this;
        }

        private void validateKeyStore(KeyStore keyStore, char[] keyStorePassword, String exceptionMessage) {
            if (isNull(keyStore) || isEmpty(keyStorePassword)) {
                throw new GenericKeyStoreException(exceptionMessage);
            }
        }

        public Builder withHostnameVerifierEnabled(boolean hostnameVerifierEnabled) {
            this.hostnameVerifierEnabled = hostnameVerifierEnabled;
            return this;
        }

        public Builder withProtocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder withTrustingAllCertificatesWithoutValidation() {
            trustingAllCertificatesWithoutValidationEnabled = true;
            oneWayAuthenticationEnabled = true;
            return this;
        }

        public SSLContextHelper build() {
            SSLContextHelper sslContextHelper = new SSLContextHelper();
            if (!oneWayAuthenticationEnabled && !twoWayAuthenticationEnabled) {
                return sslContextHelper;
            }

            validateTrustStore();
            buildHostnameVerifier(sslContextHelper);
            sslContextHelper.protocol = protocol;
            sslContextHelper.securityEnabled = true;
            sslContextHelper.includeDefaultJdkTrustStore = includeDefaultJdkTrustStore;
            sslContextHelper.trustingAllCertificatesWithoutValidationEnabled = trustingAllCertificatesWithoutValidationEnabled;

            if (twoWayAuthenticationEnabled) {
                oneWayAuthenticationEnabled = false;
            }

            buildSLLContextForOneWayAuthenticationIfEnabled(sslContextHelper);
            buildSLLContextForTwoWayAuthenticationIfEnabled(sslContextHelper);
            return sslContextHelper;
        }

        private void buildHostnameVerifier(SSLContextHelper sslContextHelper) {
            if (hostnameVerifierEnabled) {
                sslContextHelper.hostnameVerifier = new DefaultHostnameVerifier();
            } else {
                sslContextHelper.hostnameVerifier = new NoopHostnameVerifier();
            }
        }

        private void buildSLLContextForOneWayAuthenticationIfEnabled(SSLContextHelper sslContextHelper) {
            if (oneWayAuthenticationEnabled) {
                sslContextHelper.oneWayAuthenticationEnabled = true;
                sslContextHelper.trustStore = trustStore;
                sslContextHelper.trustStorePassword = trustStorePassword;
                sslContextHelper.createSSLContextWithTrustStore();
            }
        }

        private void buildSLLContextForTwoWayAuthenticationIfEnabled(SSLContextHelper sslContextHelper) {
            if (twoWayAuthenticationEnabled) {
                sslContextHelper.twoWayAuthenticationEnabled = true;
                sslContextHelper.identity = identity;
                sslContextHelper.identityPassword = identityPassword;
                sslContextHelper.trustStore = trustStore;
                sslContextHelper.trustStorePassword = trustStorePassword;
                sslContextHelper.createSSLContextWithKeyStoreAndTrustStore();
            }
        }

        private void validateTrustStore() {
            if (isNull(trustStore) && !includeDefaultJdkTrustStore && !trustingAllCertificatesWithoutValidationEnabled) {
                throw new GenericKeyStoreException(TRUST_STRATEGY_VALIDATION_EXCEPTION_MESSAGE);
            }
        }
    }
}
