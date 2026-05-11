package ru.team.novelbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Configuration
@ComponentScan("ru.team.novelbot")
public class AppConfig {
    @Bean
    AppProperties appProperties() {
        return AppProperties.fromEnv(System.getenv(), true);
    }

    @Bean
    DataSource dataSource(AppProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(properties.jdbcUrl());
        dataSource.setUsername(properties.database().user());
        dataSource.setPassword(properties.database().password());
        return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    HttpClient httpClient(AppProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        AppProperties.Llm llm = properties.llm();
        if (llm.gigachat() && !llm.gigachatVerifySsl()) {
            builder.sslContext(insecureSslContext());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("");
            builder.sslParameters(parameters);
            return builder.build();
        }
        if (llm.gigachat() && llm.gigachatCaCertPath() != null && !llm.gigachatCaCertPath().isBlank()) {
            builder.sslContext(sslContextWithAdditionalCa(Path.of(llm.gigachatCaCertPath())));
        }
        return builder.build();
    }

    private SSLContext sslContextWithAdditionalCa(Path caCertPath) {
        try {
            X509TrustManager defaultTrustManager = trustManager(null);
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            try (InputStream input = Files.newInputStream(caCertPath)) {
                Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(input);
                int index = 0;
                for (Certificate certificate : certificates) {
                    keyStore.setCertificateEntry("gigachat-ca-" + index++, certificate);
                }
                if (certificates.isEmpty()) {
                    throw new IllegalStateException("Файл GIGACHAT_CA_CERT_PATH не содержит X.509 сертификатов.");
                }
            }
            X509TrustManager customTrustManager = trustManager(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CompositeTrustManager(defaultTrustManager, customTrustManager)}, null);
            return context;
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось настроить сертификат GigaChat из GIGACHAT_CA_CERT_PATH.", ex);
        }
    }

    private SSLContext insecureSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new InsecureTrustManager()}, new SecureRandom());
            return context;
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось отключить проверку SSL для GigaChat.", ex);
        }
    }

    private X509TrustManager trustManager(KeyStore keyStore) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("X509TrustManager не найден."));
    }

    private static final class CompositeTrustManager implements X509TrustManager {
        private final List<X509TrustManager> delegates;

        private CompositeTrustManager(X509TrustManager... delegates) {
            this.delegates = List.of(delegates);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, true);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, false);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<>();
            for (X509TrustManager delegate : delegates) {
                issuers.addAll(Arrays.asList(delegate.getAcceptedIssuers()));
            }
            return issuers.toArray(X509Certificate[]::new);
        }

        private void check(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    if (client) {
                        delegate.checkClientTrusted(chain, authType);
                    } else {
                        delegate.checkServerTrusted(chain, authType);
                    }
                    return;
                } catch (CertificateException ex) {
                    last = ex;
                }
            }
            throw last == null ? new CertificateException("Сертификат не принят.") : last;
        }
    }

    private static final class InsecureTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
