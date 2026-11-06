package uk.gov.hmcts.cp.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Exposes the configured Common Platform backend host used by outbound clients. */
@Service
@Getter
public class AppPropertiesBackend {

    @Value("${common-platform-query-api.base-url}")
    private String baseUrl;
}
