package com.project.bangjjack.domain.chat.application.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 채팅방 생성 이벤트. 각 서버가 받아 로컬 참여자 세션을 새 방에 구독시킨다.
 *
 * @param delayed Redis 발행 실패 후 재시도로 늦게 전달된 이벤트. 그 사이 발행된 메시지를 놓쳤을 수 있어,
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
