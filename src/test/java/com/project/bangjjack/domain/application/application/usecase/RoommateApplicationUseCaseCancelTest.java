package com.project.bangjjack.domain.application.application.usecase;

import com.project.bangjjack.domain.application.application.exception.ApplicationCancelForbiddenException;
import com.project.bangjjack.domain.application.application.exception.InvalidApplicationStatusException;
import com.project.bangjjack.domain.application.domain.entity.ApplicationStatus;
import com.project.bangjjack.domain.application.domain.entity.RoommateApplication;
import com.project.bangjjack.domain.application.domain.service.RoommateApplicationCreateService;
import com.project.bangjjack.domain.application.domain.service.RoommateApplicationGetService;
import com.project.bangjjack.domain.chat.application.event.ChatMessageSentEvent;
import com.project.bangjjack.domain.chat.application.exception.ChatRoomNotFoundException;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomCreateService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
import com.project.bangjjack.domain.post.domain.entity.RoomSize;
import com.project.bangjjack.domain.post.domain.entity.RoommatePost;
import com.project.bangjjack.domain.post.domain.service.RoommatePostGetService;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupGetService;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupMemberCreateService;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupMemberGetService;
import com.project.bangjjack.domain.user.domain.entity.Campus;
import com.project.bangjjack.domain.user.domain.entity.Dormitory;
import com.project.bangjjack.domain.user.domain.entity.Gender;
import com.project.bangjjack.domain.user.domain.entity.Semester;
import com.project.bangjjack.domain.user.domain.entity.User;
import com.project.bangjjack.domain.user.domain.service.UserGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatStateUpdateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RoommateApplicationUseCaseCancelTest {

    @Mock
    private UserGetService userGetService;

    @Mock
    private RoommatePostGetService roommatePostGetService;

    @Mock
    private RoommateApplicationGetService roommateApplicationGetService;

    @Mock
    private RoommateApplicationCreateService roommateApplicationCreateService;

    @Mock
    private ChatRoomGetService chatRoomGetService;

    @Mock
    private ChatRoomCreateService chatRoomCreateService;

    @Mock
    private ChatSaveService chatSaveService;

    @Mock
    private RoommateGroupGetService roommateGroupGetService;

    @Mock
    private RoommateGroupMemberGetService roommateGroupMemberGetService;

    @Mock
    private RoommateGroupMemberCreateService roommateGroupMemberCreateService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock private ChatStateUpdateService chatStateUpdateService;

    @InjectMocks
    private RoommateApplicationUseCase roommateApplicationUseCase;

    private static final Long AUTHOR_ID = 1L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long POST_ID = 100L;
    private static final Long APPLICATION_ID = 999L;
    private static final Long CHAT_ROOM_ID = 500L;
    private static final Long CHAT_ID = 7777L;
    private static final String CANCELED_MESSAGE = "룸메이트 신청을 취소했습니다.";

    private User user(Long id, String providerId, String email, Gender gender) {
        User user = User.create(providerId, "유저", email, null);
        user.completeOnboarding(2000, 2, gender, Campus.GLOBAL_CAMPUS, null, Semester.SIXTEEN_WEEKS, Dormitory.DORM_1);
        user.completeChecklistRegistration();
        user.completeRoommatePreferenceRegistration();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private RoommateApplication application(ApplicationStatus status) {
        User author = user(AUTHOR_ID, "provider-author", "author@gachon.ac.kr", Gender.FEMALE);
        User applicant = user(APPLICANT_ID, "provider-applicant", "applicant@gachon.ac.kr", Gender.MALE);
        RoommatePost post = RoommatePost.create(
                author, "제목", "내용", RoomSize.TWO_PERSON, 1,
                Semester.SIXTEEN_WEEKS, Dormitory.DORM_2);
        ReflectionTestUtils.setField(post, "id", POST_ID);
        RoommateApplication application = RoommateApplication.create(post, applicant);
        ReflectionTestUtils.setField(application, "id", APPLICATION_ID);
        ReflectionTestUtils.setField(application, "status", status);
        return application;
    }

    private ChatRoom chatRoom() {
        ChatRoom room = ChatRoom.createDirect(AUTHOR_ID, ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
        ReflectionTestUtils.setField(room, "id", CHAT_ROOM_ID);
        return room;
    }

    private void stubChatSaved(ChatRoom room) {
        given(chatSaveService.save(eq(APPLICANT_ID), eq(room), eq(CANCELED_MESSAGE), eq(MessageType.APPLICATION_CANCELED), eq(APPLICATION_ID)))
                .willAnswer(invocation -> {
                    Chat chat = Chat.create(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            MessageType.APPLICATION_CANCELED,
                            APPLICATION_ID,
                            1L);
                    ReflectionTestUtils.setField(chat, "id", CHAT_ID);
                    return chat;
                });
    }

    @Nested
    @DisplayName("룸메이트 신청 취소 시")
    class CancelApplication {

        @Test
        @DisplayName("신청자 본인이 PENDING 신청을 취소하면 status=CANCELED 갱신, 취소 안내 채팅 전송, 채팅방 카테고리가 GENERAL로 복원된다")
        void 정상_취소() {
            // given
            RoommateApplication application = application(ApplicationStatus.PENDING);
            ChatRoom chatRoom = chatRoom();
            chatRoom.updateCategory(ChatRoomCategory.APPLICATION);

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.of(chatRoom));
            stubChatSaved(chatRoom);

            // when
            roommateApplicationUseCase.cancelApplication(APPLICANT_ID, APPLICATION_ID);

            // then
            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.CANCELED);
            org.mockito.Mockito.verify(chatStateUpdateService).updateCategory(chatRoom.getId(), ChatRoomCategory.GENERAL);
            then(chatSaveService).should().save(APPLICANT_ID, chatRoom, CANCELED_MESSAGE, MessageType.APPLICATION_CANCELED, APPLICATION_ID);
        }

        @Test
        @DisplayName("취소 시 ChatMessageSentEvent가 1회 발행되며 senderId=applicantId, content=취소 문구로 검증된다")
        void 채팅_브로드캐스트_이벤트_발행() {
            // given
            RoommateApplication application = application(ApplicationStatus.PENDING);
            ChatRoom chatRoom = chatRoom();

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.of(chatRoom));
            stubChatSaved(chatRoom);

            // when
            roommateApplicationUseCase.cancelApplication(APPLICANT_ID, APPLICATION_ID);

            // then
            ArgumentCaptor<ChatMessageSentEvent> captor = ArgumentCaptor.forClass(ChatMessageSentEvent.class);
            then(eventPublisher).should().publishEvent(captor.capture());
            ChatMessageSentEvent event = captor.getValue();
            assertThat(event.roomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(event.broadcast().messageId()).isEqualTo(CHAT_ID);
            assertThat(event.broadcast().senderId()).isEqualTo(APPLICANT_ID);
            assertThat(event.broadcast().content()).isEqualTo(CANCELED_MESSAGE);
        }

        @Test
        @DisplayName("작성자(author)가 취소 시도하면 ApplicationCancelForbiddenException이 발생한다")
        void 작성자_호출_예외() {
            RoommateApplication application = application(ApplicationStatus.PENDING);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);

            assertThatThrownBy(() -> roommateApplicationUseCase.cancelApplication(AUTHOR_ID, APPLICATION_ID))
                    .isInstanceOf(ApplicationCancelForbiddenException.class);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
            then(chatSaveService).should(never()).save(any(), any(), any(), any(), any());
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("제3자가 취소 시도하면 ApplicationCancelForbiddenException이 발생한다")
        void 제3자_호출_예외() {
            RoommateApplication application = application(ApplicationStatus.PENDING);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);

            Long otherUserId = 999L;
            assertThatThrownBy(() -> roommateApplicationUseCase.cancelApplication(otherUserId, APPLICATION_ID))
                    .isInstanceOf(ApplicationCancelForbiddenException.class);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
            then(chatSaveService).should(never()).save(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("이미 ACCEPTED 상태인 신청을 취소 시도하면 InvalidApplicationStatusException이 발생한다")
        void 이미_ACCEPTED_상태_예외() {
            RoommateApplication application = application(ApplicationStatus.ACCEPTED);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);

            assertThatThrownBy(() -> roommateApplicationUseCase.cancelApplication(APPLICANT_ID, APPLICATION_ID))
                    .isInstanceOf(InvalidApplicationStatusException.class);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.ACCEPTED);
            then(chatSaveService).should(never()).save(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("이미 REJECTED 상태인 신청을 취소 시도하면 InvalidApplicationStatusException이 발생한다")
        void 이미_REJECTED_상태_예외() {
            RoommateApplication application = application(ApplicationStatus.REJECTED);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);

            assertThatThrownBy(() -> roommateApplicationUseCase.cancelApplication(APPLICANT_ID, APPLICATION_ID))
                    .isInstanceOf(InvalidApplicationStatusException.class);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        }

        @Test
        @DisplayName("이미 CANCELED 상태인 신청을 다시 취소 시도하면 InvalidApplicationStatusException이 발생한다 (idempotent 거부)")
        void 이미_CANCELED_상태_예외() {
            RoommateApplication application = application(ApplicationStatus.CANCELED);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);

            assertThatThrownBy(() -> roommateApplicationUseCase.cancelApplication(APPLICANT_ID, APPLICATION_ID))
                    .isInstanceOf(InvalidApplicationStatusException.class);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.CANCELED);
        }

        @Test
        @DisplayName("신청자-작성자 채팅방이 존재하지 않으면 ChatRoomNotFoundException이 발생한다")
        void 채팅방_미존재_예외() {
            RoommateApplication application = application(ApplicationStatus.PENDING);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.empty());

            assertThatThrownBy(() -> roommateApplicationUseCase.cancelApplication(APPLICANT_ID, APPLICATION_ID))
                    .isInstanceOf(ChatRoomNotFoundException.class);
        }
    }
}
