package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("PubSubTracing")
class PubSubTracingTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String PAYLOAD = "{\"messageId\":10,\"roomId\":1}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Tracer tracer;
    private Propagator propagator;
    private PubSubTracing pubSubTracing;

    @BeforeEach
    void setUp() {
        tracer = mock(Tracer.class);
        propagator = mock(Propagator.class);
        pubSubTracing = new PubSubTracing(tracer, propagator, objectMapper);
    }

    @Test
    @DisplayName("발행: 추적 중이 아니면 페이로드를 그대로 발행한다")
    void inject_withoutSpan_keepsPayload() {
        given(tracer.currentSpan()).willReturn(null);

        assertThat(pubSubTracing.inject(PAYLOAD)).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("발행: 현재 span 의 traceparent 를 _trace 필드에 싣고 기존 필드는 유지한다")
    @SuppressWarnings("unchecked")
    void inject_addsTraceField() throws Exception {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        given(tracer.currentSpan()).willReturn(span);
        given(span.context()).willReturn(context);
        doAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(1);
            Propagator.Setter<Map<String, String>> setter = invocation.getArgument(2);
            setter.set(carrier, "traceparent", TRACEPARENT);
            return null;
        }).when(propagator).inject(any(), any(), any());

        JsonNode published = objectMapper.readTree(pubSubTracing.inject(PAYLOAD));

        assertThat(published.get("messageId").asLong()).isEqualTo(10L);
        assertThat(published.get(PubSubTracing.TRACE_FIELD).get("traceparent").asText()).isEqualTo(TRACEPARENT);
    }

    @Test
    @DisplayName("수신: _trace 필드로 발행 측 트레이스를 잇고, 핸들러에는 _trace 를 뗀 본문을 넘긴다")
    @SuppressWarnings("unchecked")
    void receive_continuesTrace_andStripsField() throws Exception {
        Span.Builder builder = mock(Span.Builder.class, RETURNS_SELF);
        Span span = mock(Span.class);
        given(builder.start()).willReturn(span);
        given(propagator.extract(any(), any())).willReturn(builder);
        given(tracer.withSpan(span)).willReturn(mock(Tracer.SpanInScope.class));
        String received = "{\"messageId\":10,\"roomId\":1,\"_trace\":{\"traceparent\":\"" + TRACEPARENT + "\"}}";
        AtomicReference<JsonNode> body = new AtomicReference<>();

        pubSubTracing.receive("chat.pubsub.receive", "chat:room:1", received, body::set);

        ArgumentCaptor<Map<String, String>> carrier = ArgumentCaptor.forClass(Map.class);
        then(propagator).should().extract(carrier.capture(), any());
        assertThat(carrier.getValue()).containsEntry("traceparent", TRACEPARENT);
        assertThat(body.get().has(PubSubTracing.TRACE_FIELD)).isFalse();
        assertThat(body.get().get("messageId").asLong()).isEqualTo(10L);
        then(span).should().end();
    }

    @Test
    @DisplayName("수신: _trace 필드가 없는 메시지(이전 버전 발행)도 새 트레이스로 정상 처리한다")
    void receive_withoutTraceField_startsNewTrace() throws Exception {
        Span.Builder builder = mock(Span.Builder.class, RETURNS_SELF);
        Span span = mock(Span.class);
        given(builder.start()).willReturn(span);
        given(tracer.spanBuilder()).willReturn(builder);
        given(tracer.withSpan(span)).willReturn(mock(Tracer.SpanInScope.class));
        AtomicReference<JsonNode> body = new AtomicReference<>();

        pubSubTracing.receive("chat.pubsub.receive", "chat:room:1", PAYLOAD, body::set);

        then(propagator).should(never()).extract(any(), any());
        assertThat(body.get()).isEqualTo(objectMapper.readTree(PAYLOAD));
        then(span).should().end();
    }
}
