package com.project.bangjjack.global.config.security;

import org.springframework.stereotype.Component;

@Component
public class PermitUrlConfig {

    public String[] publicUrls() {
        return new String[]{
                "/oauth2/authorization/**",
                "/auth/login/**",
                "/api/v1/auth/token",
                "/api/v1/dev/**", // TODO: dev 환경에서만 해당 경로 허용하도록 확장
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-resources/**",
                "/ws/chat/**",
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/prometheus" // management 포트(8081) 전용, 외부 미노출
        };
    }
}
