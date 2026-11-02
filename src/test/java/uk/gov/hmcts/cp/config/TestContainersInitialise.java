package uk.gov.hmcts.cp.config;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots a reusable PostgreSQL test container and injects its connection details into Spring tests.
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases") // an initializer, not a JUnit test class
public class TestContainersInitialise
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    // same image as docker-compose.yml
    private static final PostgreSQLContainer<?> POSTGRE_SQL_CONTAINER =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("casemanagement")
                    .withUsername("casemanagement")
                    .withPassword("casemanagement")
                    .withReuse(true);

    static {
        POSTGRE_SQL_CONTAINER.start(); // start once
    }

    /**
     * Applies container-backed datasource properties to the test application context.
     *
     * @param context Spring application context under initialization
     */
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRE_SQL_CONTAINER.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRE_SQL_CONTAINER.getUsername(),
                "spring.datasource.password=" + POSTGRE_SQL_CONTAINER.getPassword()
        ).applyTo(context.getEnvironment());
    }
}
