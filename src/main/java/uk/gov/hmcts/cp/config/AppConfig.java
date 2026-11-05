package uk.gov.hmcts.cp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    /**
     * CJSCPPUID is not a default header here: it is the caller's own identity, forwarded
     * per-request by each client rather than fixed on this shared bean.
     */
    @Bean
    public RestClient restClient(final RestClient.Builder builder, final AppPropertiesBackend appPropertiesBackend) {
        return builder.baseUrl(appPropertiesBackend.getBaseUrl()).build();
    }
}
