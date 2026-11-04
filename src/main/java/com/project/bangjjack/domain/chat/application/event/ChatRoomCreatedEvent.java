package com.project.bangjjack.domain.chat.application.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 채팅방 생성 이벤트. 각 서버가 받아 로컬 참여자 세션을 새 방에 구독시킨다.
 *
 * @param delayed 구독 후 메시지 재동기화가 필요한 이벤트. 즉시 발행도 실행기 대기나 발행 중 지연으로
 *                메시지를 놓칠 수 있어 true 로 전송한다.
 *                수신 서버는 새로 구독한 세션에 재동기화 신호를 보낸다.
 *                false 면 JSON 에서 빼서, 이 필드를 모르는 이전 버전 서버(알 수 없는 필드 거부)와 일반 이벤트 형식을 유지한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRoomCreatedEvent(
        Long roomId,
        List<Long> participantIds,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean delayed
) {

    public ChatRoomCreatedEvent(Long roomId, List<Long> participantIds) {
        this(roomId, participantIds, false);
    }

    public ChatRoomCreatedEvent asDelayed() {
        return new ChatRoomCreatedEvent(roomId, participantIds, true);
    }
}
