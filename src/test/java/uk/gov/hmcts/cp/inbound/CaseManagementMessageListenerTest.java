package uk.gov.hmcts.cp.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.cp.config.ServiceBusHealthIndicator;

class CaseManagementMessageListenerTest {

    private final AtomicReference<InboundMessage> handled = new AtomicReference<>();
    private InboundMessageHandler handler = handled::set;
    private ServiceBusReceivedMessageContext context;
    private ServiceBusReceivedMessage message;
    private final ServiceBusHealthIndicator health =
            new ServiceBusHealthIndicator(Duration.ofSeconds(60), Clock.systemUTC());

    @BeforeEach
    void setUp() {
        context = mock(ServiceBusReceivedMessageContext.class);
        message = mock(ServiceBusReceivedMessage.class);
        when(context.getMessage()).thenReturn(message);
        when(message.getMessageId()).thenReturn("msg-1");
        when(message.getSubject()).thenReturn("casemanagement.example");
        when(message.getDeliveryCount()).thenReturn(0L);
    }

    private void deliver(final String body) {
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        new CaseManagementMessageListener(handler, health).onMessage(context);
    }

    @Test
    void well_formed_message_is_handed_to_the_handler_and_completed() {
        deliver("{\"groupId\":\"1b4e28ba-2fa1-11d2-883f-0016d3cca427\"}");

        assertThat(handled.get().messageId()).isEqualTo("msg-1");
        assertThat(handled.get().subject()).isEqualTo("casemanagement.example");
        assertThat(handled.get().body().path("groupId").stringValue())
                .isEqualTo("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        verify(context).complete();
        verify(context, never()).abandon();
    }

    @Test
    void body_that_is_not_json_is_dead_lettered_without_reaching_the_handler() {
        deliver("not json");

        assertThat(handled.get()).isNull();
        final ArgumentCaptor<DeadLetterOptions> options = ArgumentCaptor.forClass(DeadLetterOptions.class);
        verify(context).deadLetter(options.capture());
        assertThat(options.getValue().getDeadLetterReason()).isEqualTo("VALIDATION");
        verify(context, never()).complete();
    }

    @Test
    void json_that_is_not_an_object_is_dead_lettered() {
        deliver("[1,2]");

        verify(context).deadLetter(any(DeadLetterOptions.class));
    }

    @Test
    void handler_refusal_is_dead_lettered_with_its_reason() {
        handler = refused -> {
            throw new InvalidMessageException("groupId missing");
        };
        deliver("{}");

        final ArgumentCaptor<DeadLetterOptions> options = ArgumentCaptor.forClass(DeadLetterOptions.class);
        verify(context).deadLetter(options.capture());
        assertThat(options.getValue().getDeadLetterErrorDescription()).isEqualTo("groupId missing");
    }

    @Test
    void unexpected_handler_failure_is_abandoned_for_redelivery() {
        handler = failing -> {
            throw new IllegalStateException("database down");
        };
        deliver("{}");

        verify(context).abandon();
        verify(context, never()).complete();
    }

    @Test
    void lost_lock_on_settlement_is_swallowed_and_not_retried() {
        doThrow(new IllegalStateException("settlement failed",
                new AmqpException(false, AmqpErrorCondition.MESSAGE_LOCK_LOST, "lock lost", null)))
                .when(context).complete();

        deliver("{}");

        verify(context).complete();
        verify(context, never()).abandon();
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void refused_settlement_marks_health_down() {
        doThrow(new IllegalStateException("link closed")).when(context).complete();

        deliver("{}");

        assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
    }
}
