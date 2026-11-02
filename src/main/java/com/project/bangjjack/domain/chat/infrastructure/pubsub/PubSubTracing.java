package com.project.bangjjack.domain.chat.infrastructure.pubsub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Pub/Sub 메시지에 trace context 를 실어 서버 간 트레이스를 잇는다.
 *
 * Redis Pub/Sub 에는 헤더가 없으므로 JSON 페이로드의 {@value #TRACE_FIELD} 필드에 W3C traceparent 를 넣는다.
 * 수신 측은 이 필드를 떼어낸 뒤 원래 DTO 로 역직렬화하므로, 클라이언트(WebSocket)에는 노출되지 않는다.
 * 필드가 없는 메시지(트레이싱 이전 버전이 발행한 메시지)는 새 트레이스로 처리한다.
 */
@Component
public class PubSubTracing {

    static final String TRACE_FIELD = "_trace";

    private final Tracer tracer;
    private final Propagator propagator;
    private final ObjectMapper objectMapper;

    public PubSubTracing(Tracer tracer, Propagator propagator, ObjectMapper objectMapper) {
        this.tracer = tracer;
        this.propagator = propagator;
        this.objectMapper = objectMapper;
    }

    /** 트레이싱 없이 동작하는 인스턴스 (단위 테스트용). */
    public static PubSubTracing noop(ObjectMapper objectMapper) {
        return new PubSubTracing(Tracer.NOOP, Propagator.NOOP, objectMapper);
    }

    /**
     * 현재 span 의 trace context 를 발행 페이로드에 싣는다. 추적 중이 아니면 페이로드를 그대로 반환한다.
     */
    public String inject(String json) {
        Span current = tracer.currentSpan();
        if (current == null || current.isNoop()) {
            return json;
        }
        Map<String, String> carrier = new LinkedHashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        if (carrier.isEmpty()) {
            return json;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!(node instanceof ObjectNode objectNode)) {
                return json;
            }
            objectNode.set(TRACE_FIELD, objectMapper.valueToTree(carrier));
            return objectMapper.writeValueAsString(objectNode);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    /**
     * 수신 처리를 consumer span 으로 감싼다. 페이로드에 trace context 가 있으면 발행 측 트레이스에 이어 붙인다.
     * handler 에는 trace 필드를 떼어낸 본문이 전달된다.
     */
    public void receive(String spanName, CharSequence channel, String json, ReceiveHandler handler) throws Exception {
        JsonNode body = objectMapper.readTree(json);
        Map<String, String> carrier = removeCarrier(body);
        Span.Builder builder = carrier.isEmpty() ? tracer.spanBuilder() : propagator.extract(carrier, Map::get);
        Span span = builder.name(spanName)
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "redis")
                .tag("messaging.destination.name", String.valueOf(channel))
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            handler.handle(body);
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** 현재 span 에 태그를 단다 (예: 메시지 ID 로 트레이스 검색). */
    public void tag(String key, Object value) {
        Span current = tracer.currentSpan();
        if (current != null && value != null) {
            current.tag(key, String.valueOf(value));
        }
    }

    /** 현재 span 을 실패로 표시한다. 예외를 삼키는 구간(발행 실패 등)에서 손실 지점을 트레이스에 남기기 위해 사용. */
    public void error(Throwable throwable) {
        Span current = tracer.currentSpan();
        if (current != null) {
            current.error(throwable);
        }
    }

    private Map<String, String> removeCarrier(JsonNode body) {
        if (!(body instanceof ObjectNode objectNode) || !objectNode.has(TRACE_FIELD)) {
            return Map.of();
        }
        JsonNode trace = objectNode.remove(TRACE_FIELD);
        Map<String, String> carrier = new HashMap<>();
        trace.fields().forEachRemaining(entry -> carrier.put(entry.getKey(), entry.getValue().asText()));
        return carrier;
    }

    @FunctionalInterface
    public interface ReceiveHandler {
        void handle(JsonNode body) throws Exception;
    }
}
