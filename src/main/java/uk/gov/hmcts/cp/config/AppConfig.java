package uk.gov.hmcts.cp.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    /**
     * CJSCPPUID is not a default header here: it is the caller's own identity, forwarded
     * per-request by each client rather than fixed on this shared bean.
     */
    @Bean
    public RestClient restClient(final RestClient.Builder builder, final AppPropertiesBackend appPropertiesBackend) {
        return builder
                .requestFactory(http11RequestFactory())
                .baseUrl(appPropertiesBackend.getBaseUrl())
                .build();
    }

    /**
     * HTTP/1.1 only. The JDK client otherwise tries an h2c upgrade on plain-HTTP requests, which
     * fails for a POST with a body (such as Progression commands) against servers that do not
     * support it.
     */
    public static ClientHttpRequestFactory http11RequestFactory() {
        return new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
    }
}
