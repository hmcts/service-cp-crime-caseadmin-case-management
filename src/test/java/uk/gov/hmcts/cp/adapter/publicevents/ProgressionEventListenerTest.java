package uk.gov.hmcts.cp.adapter.publicevents;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.adapter.publicevents.ProgressionEventListener.GROUP_PROSECUTION_CASES_CREATED;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.services.ProgressionEventSink;

class ProgressionEventListenerTest {

    private static final UUID GROUP_ID = UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");

    private final ProgressionEventSink sink = mock(ProgressionEventSink.class);
    private final DeliveryObserver observer = mock(DeliveryObserver.class);
    private final ProgressionEventListener listener = new ProgressionEventListener(sink, observer);

    static String envelope(final String name, final String groupId) {
        return """
                {"_metadata":{"id":"%s","name":"%s","createdAt":"2026-11-17T10:00:00Z"},
                 "groupId":%s}""".formatted(UUID.randomUUID(), name,
                groupId == null ? "null" : '"' + groupId + '"');
    }

    private static TextMessage message(final String cppName, final String body) throws JMSException {
        final TextMessage message = mock(TextMessage.class);
        when(message.getStringProperty("CPPNAME")).thenReturn(cppName);
        when(message.getText()).thenReturn(body);
        return message;
    }

    @Test
    void group_prosecution_cases_created_is_applied_with_its_group_id() throws JMSException {
        final String event = envelope(GROUP_PROSECUTION_CASES_CREATED, GROUP_ID.toString());
        listener.onPublicEvent(message(GROUP_PROSECUTION_CASES_CREATED, event));

        verify(observer).recordDelivery();
        verify(sink).groupProsecutionCasesCreated(GROUP_ID, event);
    }

    @Test
    void other_event_names_are_ignored() throws JMSException {
        listener.onPublicEvent(message("public.progression.prosecution-case-created",
                envelope("public.progression.prosecution-case-created", GROUP_ID.toString())));

        verify(observer).recordDelivery();
        verify(sink, never()).groupProsecutionCasesCreated(any(), any());
    }

    @Test
    void header_and_envelope_disagreeing_is_dropped() throws JMSException {
        listener.onPublicEvent(message(GROUP_PROSECUTION_CASES_CREATED,
                envelope("public.progression.something-else", GROUP_ID.toString())));

        verify(sink, never()).groupProsecutionCasesCreated(any(), any());
    }

    @Test
    void missing_or_malformed_group_id_is_dropped() throws JMSException {
        listener.onPublicEvent(message(GROUP_PROSECUTION_CASES_CREATED,
                envelope(GROUP_PROSECUTION_CASES_CREATED, null)));
        listener.onPublicEvent(message(GROUP_PROSECUTION_CASES_CREATED,
                envelope(GROUP_PROSECUTION_CASES_CREATED, "not-a-uuid")));

        verify(sink, never()).groupProsecutionCasesCreated(any(), any());
    }

    @Test
    void non_text_message_is_dropped_not_redelivered() {
        listener.onPublicEvent(mock(BytesMessage.class));

        verify(observer).recordDelivery();
        verify(sink, never()).groupProsecutionCasesCreated(any(), any());
    }

    @Test
    void unreadable_body_is_dropped() throws JMSException {
        listener.onPublicEvent(message(GROUP_PROSECUTION_CASES_CREATED, "not json"));

        verify(sink, never()).groupProsecutionCasesCreated(any(), any());
    }

    @Test
    void failure_to_apply_is_rethrown_so_the_broker_redelivers() throws JMSException {
        doThrow(new IllegalStateException("db down")).when(sink).groupProsecutionCasesCreated(any(), any());
        final TextMessage message = message(GROUP_PROSECUTION_CASES_CREATED,
                envelope(GROUP_PROSECUTION_CASES_CREATED, GROUP_ID.toString()));

        assertThatThrownBy(() -> listener.onPublicEvent(message))
                .isInstanceOf(IllegalStateException.class);
    }
}
