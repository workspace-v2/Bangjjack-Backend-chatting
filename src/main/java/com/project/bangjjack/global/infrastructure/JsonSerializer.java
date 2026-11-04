package com.project.bangjjack.global.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonSerializer {

    private final ObjectMapper objectMapper;

    public String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 직렬화 실패: " + obj.getClass().getSimpleName(), e);
        }
    }

    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 역직렬화 실패: " + type.getSimpleName(), e);
        }
    }
}
