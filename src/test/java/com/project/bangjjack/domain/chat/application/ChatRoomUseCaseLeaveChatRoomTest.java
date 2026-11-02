package com.project.bangjjack.domain.chat.application;

import com.project.bangjjack.domain.chat.application.exception.AlreadyLeftChatRoomException;
import com.project.bangjjack.domain.chat.application.usecase.ChatRoomUseCase;
import com.project.bangjjack.domain.chat.domain.service.ChatMessageGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomCreateService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.user.domain.service.UserGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatStateUpdateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;


import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ChatRoomUseCaseLeaveChatRoomTest {

    @Mock private ChatRoomCreateService chatRoomCreateService;
    @Mock private ChatRoomGetService chatRoomGetService;
    @Mock private ChatMessageGetService chatMessageGetService;
    @Mock private UserGetService userGetService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @Mock private ChatStateUpdateService chatStateUpdateService;

    @InjectMocks
    private ChatRoomUseCase chatRoomUseCase;

    @Test
    @DisplayName("방 나가기는 상태 변경 서비스에 위임한다")
    void leave_delegatesStateUpdate() {
        chatRoomUseCase.leaveChatRoom(1L, 10L);
        org.mockito.Mockito.verify(chatStateUpdateService).leave(1L, 10L);
    }

    @Test
    @DisplayName("상태 변경 서비스의 참여자 검증 실패를 전파한다")
    void leave_propagatesValidationFailure() {
        org.mockito.Mockito.doThrow(new AlreadyLeftChatRoomException())
                .when(chatStateUpdateService).leave(1L, 10L);
        assertThatThrownBy(() -> chatRoomUseCase.leaveChatRoom(1L, 10L))
                .isInstanceOf(AlreadyLeftChatRoomException.class);
    }
}
