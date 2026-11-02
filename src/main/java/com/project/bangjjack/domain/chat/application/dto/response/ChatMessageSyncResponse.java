package com.project.bangjjack.domain.chat.application.dto.response;

import java.util.List;

/**
 * 동기화 응답.
 *
 * @param messages  afterSeq 이후 메시지 (seq 오름차순)
 * @param hasNext   더 가져올 메시지가 있으면 true -> syncedSeq 를 afterSeq 로 다시 호출
 * @param syncedSeq 클라이언트가 여기까지 받았다고 기록할 seq.
 *                  재입장 이전 메시지처럼 볼 수 없는 seq 도 건너뛰므로, 이 값을 기준으로 다음 gap 을 판단한다.
 */
public record ChatMessageSyncResponse(
        List<ChatMessageResponse> messages,
        boolean hasNext,
        long syncedSeq
) {
    public static ChatMessageSyncResponse of(List<ChatMessageResponse> messages, boolean hasNext, long syncedSeq) {
        return new ChatMessageSyncResponse(messages, hasNext, syncedSeq);
    }
}
