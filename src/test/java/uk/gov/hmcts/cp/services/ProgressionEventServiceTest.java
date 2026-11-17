package uk.gov.hmcts.cp.services;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ProgressionEventServiceTest {

    @Test
    void logs_the_whole_event_at_info() {
        final Logger logger = (Logger) LoggerFactory.getLogger(ProgressionEventService.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        final UUID groupId = UUID.randomUUID();
        final String event = "{\"_metadata\":{\"name\":\"public.progression.group-prosecution-cases-created\"},"
                + "\"groupId\":\"" + groupId + "\"}";
        try {
            new ProgressionEventService().groupProsecutionCasesCreated(groupId, event);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.INFO);
            assertThat(line.getFormattedMessage()).contains(event).contains(groupId.toString());
        });
    }
}
