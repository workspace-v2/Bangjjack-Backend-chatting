package com.project.bangjjack.global.config.tracing;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
public class TracingConfig {

    /**
     * 트레이스 노이즈 제거.
     * - actuator: ALB 헬스체크·Prometheus 수집이 수 초마다 들어와 메시지 트레이스를 덮는다
     * - Spring Security 필터 체인: 요청마다 하위 span 이 수십 개 생긴다
     */
    @Bean
    public ObservationPredicate noiseObservationPredicate() {
        return (name, context) -> {
            if (name.startsWith("spring.security.")) {
                return false;
            }
            if (context instanceof ServerRequestObservationContext serverContext) {
                return !serverContext.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
