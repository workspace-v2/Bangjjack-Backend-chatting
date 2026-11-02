package com.project.bangjjack.domain.chat.presentation;

import com.project.bangjjack.domain.chat.application.dto.request.CreateChatRoomRequest;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.application.dto.response.ChatMessagePageResponse;
import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageSyncResponse;
import com.project.bangjjack.domain.chat.application.dto.response.ChatRoomListResponse;
import com.project.bangjjack.domain.chat.application.dto.response.ChatRoomResponse;
import com.project.bangjjack.domain.chat.application.usecase.ChatMessageUseCase;
import com.project.bangjjack.domain.chat.application.usecase.ChatRoomUseCase;
import com.project.bangjjack.domain.chat.presentation.response.ChatRoomResponseCode;
import com.project.bangjjack.global.annotation.CurrentMemberId;
import com.project.bangjjack.global.common.response.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "ChatRoom", description = "채팅방 관련 API")
@Validated
@RestController
@RequestMapping("/api/v1/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomUseCase chatRoomUseCase;
    private final ChatMessageUseCase chatMessageUseCase;

    @Operation(summary = "내 채팅방 목록 조회", description = "최신 메시지 순으로 커서 기반 페이지네이션 조회합니다.")
    @GetMapping
    public CommonResponse<ChatRoomListResponse> getMyChatRooms(
            @CurrentMemberId Long currentMemberId,
            @RequestParam(required = false) ChatRoomCategory category,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(currentMemberId, category, cursor, size);
        return CommonResponse.success(ChatRoomResponseCode.CHAT_ROOM_LIST_FOUND, response);
    }

    @Operation(summary = "1:1 채팅방 생성", description = "특정 유저와 1:1 채팅방을 생성합니다. 이미 존재하는 경우 기존 채팅방을 반환합니다.")
    @PostMapping
    public CommonResponse<ChatRoomResponse> createDirectRoom(
            @CurrentMemberId Long currentMemberId,
            @RequestBody @Valid CreateChatRoomRequest request) {
        ChatRoomResponse response = chatRoomUseCase.createDirectRoom(currentMemberId, request);
        ChatRoomResponseCode responseCode = response.isNewRoom()
                ? ChatRoomResponseCode.CHAT_ROOM_CREATED
                : ChatRoomResponseCode.CHAT_ROOM_FOUND;
        return CommonResponse.success(responseCode, response);
    }

    @Operation(summary = "채팅방 나가기", description = "채팅방에서 나갑니다. 모든 참여자가 나가면 채팅방이 CLOSED 처리됩니다.")
    @DeleteMapping("/{roomId}/participants/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveChatRoom(
            @CurrentMemberId Long currentMemberId,
            @PathVariable Long roomId) {
        chatRoomUseCase.leaveChatRoom(roomId, currentMemberId);
    }

    @Operation(
            summary = "채팅 메시지 조회",
            description = "채팅방의 메시지를 커서 기반 페이지네이션으로 조회합니다. \n첫 페이지 조회 시 cursor는 생략하며, 다음 페이지 조회 시 이전 응답의 nextCursor 값을 cursor로 사용합니다."
    )
    @GetMapping("/{roomId}/messages")
    public CommonResponse<ChatMessagePageResponse> getMessages(
            @CurrentMemberId Long currentMemberId,
            @PathVariable Long roomId,
            @RequestParam(required = false) @Min(1) Long cursor,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size) {
        ChatMessagePageResponse response = chatMessageUseCase.getMessages(currentMemberId, roomId, cursor, size);
        return CommonResponse.success(ChatRoomResponseCode.CHAT_ROOM_MESSAGES_FOUND, response);
    }

    @Operation(
            summary = "채팅 메시지 동기화",
            description = "afterSeq(마지막으로 받은 seq) 이후 메시지를 seq 오름차순으로 반환합니다. "
                    + "WebSocket 재연결, 수신 seq 건너뜀(gap), SYNC_REQUIRED 수신 시 호출합니다. \n"
                    + "hasNext 가 true 면 응답의 syncedSeq 를 afterSeq 로 다시 호출하고, 끝나면 syncedSeq 를 마지막 수신 seq 로 기록합니다. "
                    + "읽음 처리는 하지 않습니다."
    )
    @GetMapping("/{roomId}/messages/sync")
    public CommonResponse<ChatMessageSyncResponse> syncMessages(
            @CurrentMemberId Long currentMemberId,
            @PathVariable Long roomId,
            @RequestParam @Min(0) long afterSeq,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) {
        ChatMessageSyncResponse response = chatMessageUseCase.syncMessages(currentMemberId, roomId, afterSeq, size);
        return CommonResponse.success(ChatRoomResponseCode.CHAT_ROOM_MESSAGES_SYNCED, response);
    }
}
