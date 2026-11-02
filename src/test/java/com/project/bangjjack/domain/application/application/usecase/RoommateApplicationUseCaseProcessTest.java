package com.project.bangjjack.domain.application.application.usecase;

import com.project.bangjjack.domain.application.application.dto.request.ProcessRoommateApplicationRequest;
import com.project.bangjjack.domain.application.application.dto.response.ProcessRoommateApplicationResponse;
import com.project.bangjjack.domain.application.application.exception.ApplicantAlreadyInGroupException;
import com.project.bangjjack.domain.application.application.exception.ApplicationProcessForbiddenException;
import com.project.bangjjack.domain.application.application.exception.InvalidApplicationStatusException;
import com.project.bangjjack.domain.application.application.exception.InvalidTargetStatusException;
import com.project.bangjjack.domain.application.application.exception.RecruitLimitReachedException;
import com.project.bangjjack.domain.application.domain.entity.ApplicationStatus;
import com.project.bangjjack.domain.application.domain.entity.RoommateApplication;
import com.project.bangjjack.domain.application.domain.service.RoommateApplicationGetService;
import com.project.bangjjack.domain.chat.application.event.ChatMessageSentEvent;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomCreateService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
import com.project.bangjjack.domain.post.domain.entity.RoomSize;
import com.project.bangjjack.domain.post.domain.entity.RoommatePost;
import com.project.bangjjack.domain.roommategroup.domain.entity.GroupMemberRole;
import com.project.bangjjack.domain.roommategroup.domain.entity.RoommateGroup;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupGetService;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupMemberCreateService;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupMemberGetService;
import com.project.bangjjack.domain.user.domain.entity.Campus;
import com.project.bangjjack.domain.user.domain.entity.Dormitory;
import com.project.bangjjack.domain.user.domain.entity.Gender;
import com.project.bangjjack.domain.user.domain.entity.Semester;
import com.project.bangjjack.domain.user.domain.entity.User;
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
class RoommateApplicationUseCaseProcessTest {

    @Mock
    private RoommateApplicationGetService roommateApplicationGetService;

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
    private static final Long GROUP_ID = 700L;
    private static final Long CHAT_ID = 7777L;
    private static final int RECRUIT_MEMBER_COUNT = 1;
    private static final String ACCEPTED_MESSAGE = "룸메이트 신청을 수락했습니다.";
    private static final String REJECTED_MESSAGE = "룸메이트 신청을 거절했습니다.";

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
                author, "제목", "내용", RoomSize.TWO_PERSON, RECRUIT_MEMBER_COUNT,
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

    private RoommateGroup group(RoommatePost post) {
        RoommateGroup group = RoommateGroup.create(post);
        ReflectionTestUtils.setField(group, "id", GROUP_ID);
        return group;
    }

    private void stubChatSaved(ChatRoom room, String content, MessageType messageType) {
        given(chatSaveService.save(eq(AUTHOR_ID), eq(room), eq(content), eq(messageType), eq(APPLICATION_ID)))
                .willAnswer(invocation -> {
                    Chat chat = Chat.create(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), messageType, APPLICATION_ID);
                    ReflectionTestUtils.setField(chat, "id", CHAT_ID);
                    return chat;
                });
    }

    @Nested
    @DisplayName("룸메이트 신청 처리 시")
    class ProcessApplication {

        @Test
        @DisplayName("ACCEPTED 처리 시 application.status=ACCEPTED 갱신, MEMBER 추가, 수락 채팅 전송이 함께 수행된다")
        void ACCEPTED_처리_정상() {
            // given
            RoommateApplication application = application(ApplicationStatus.PENDING);
            RoommatePost post = application.getPost();
            RoommateGroup group = group(post);
            ChatRoom chatRoom = chatRoom();

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(roommateGroupGetService.getByPostIdForUpdate(POST_ID)).willReturn(group);
            given(roommateGroupMemberGetService.countByGroupIdAndRole(GROUP_ID, GroupMemberRole.MEMBER)).willReturn(0L);
            given(roommateGroupMemberGetService.existsByUserIdAndRole(APPLICANT_ID, GroupMemberRole.MEMBER)).willReturn(false);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.of(chatRoom));
            stubChatSaved(chatRoom, ACCEPTED_MESSAGE, MessageType.APPLICATION_ACCEPTED);

            // when
            ProcessRoommateApplicationResponse response = roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.ACCEPTED));

            // then
            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.ACCEPTED);
            assertThat(response.status()).isEqualTo(ApplicationStatus.ACCEPTED);
            assertThat(response.groupId()).isEqualTo(GROUP_ID);
            assertThat(response.currentGroupMemberCount()).isEqualTo(1);
            assertThat(response.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
            then(roommateGroupMemberCreateService).should().addMember(group, application.getApplicant(), GroupMemberRole.MEMBER);
            then(chatSaveService).should().save(AUTHOR_ID, chatRoom, ACCEPTED_MESSAGE, MessageType.APPLICATION_ACCEPTED, APPLICATION_ID);
        }

        @Test
        @DisplayName("REJECTED 처리 시 application.status=REJECTED 갱신, MEMBER 미추가, 거절 채팅 전송된다")
        void REJECTED_처리_정상() {
            // given
            RoommateApplication application = application(ApplicationStatus.PENDING);
            ChatRoom chatRoom = chatRoom();

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.of(chatRoom));
            stubChatSaved(chatRoom, REJECTED_MESSAGE, MessageType.APPLICATION_REJECTED);

            // when
            ProcessRoommateApplicationResponse response = roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.REJECTED));

            // then
            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(response.status()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(response.groupId()).isNull();
            assertThat(response.currentGroupMemberCount()).isNull();
            then(roommateGroupGetService).should(never()).getByPostIdForUpdate(any());
            then(roommateGroupMemberCreateService).should(never()).addMember(any(), any(), any());
            then(chatSaveService).should().save(AUTHOR_ID, chatRoom, REJECTED_MESSAGE, MessageType.APPLICATION_REJECTED, APPLICATION_ID);
        }

        @Test
        @DisplayName("ACCEPTED/REJECTED 처리 시 ChatMessageSentEvent가 1회 발행되며 senderId=authorId, content=해당 문구로 검증된다")
        void 채팅_브로드캐스트_이벤트_발행() {
            RoommateApplication application = application(ApplicationStatus.PENDING);
            RoommatePost post = application.getPost();
            RoommateGroup group = group(post);
            ChatRoom chatRoom = chatRoom();

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(roommateGroupGetService.getByPostIdForUpdate(POST_ID)).willReturn(group);
            given(roommateGroupMemberGetService.countByGroupIdAndRole(GROUP_ID, GroupMemberRole.MEMBER)).willReturn(0L);
            given(roommateGroupMemberGetService.existsByUserIdAndRole(APPLICANT_ID, GroupMemberRole.MEMBER)).willReturn(false);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.of(chatRoom));
            stubChatSaved(chatRoom, ACCEPTED_MESSAGE, MessageType.APPLICATION_ACCEPTED);

            roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.ACCEPTED));

            ArgumentCaptor<ChatMessageSentEvent> captor = ArgumentCaptor.forClass(ChatMessageSentEvent.class);
            then(eventPublisher).should().publishEvent(captor.capture());
            ChatMessageSentEvent event = captor.getValue();
            assertThat(event.roomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(event.broadcast().messageId()).isEqualTo(CHAT_ID);
            assertThat(event.broadcast().senderId()).isEqualTo(AUTHOR_ID);
            assertThat(event.broadcast().content()).isEqualTo(ACCEPTED_MESSAGE);
        }

        @Test
        @DisplayName("body.status=PENDING 요청 시 InvalidTargetStatusException이 발생한다")
        void PENDING_타겟_요청_예외() {
            assertThatThrownBy(() -> roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.PENDING)))
                    .isInstanceOf(InvalidTargetStatusException.class);

            then(roommateApplicationGetService).should(never()).getWithPostAndUserById(any());
        }

        @Test
        @DisplayName("작성자가 아닌 사용자가 호출하면 ApplicationProcessForbiddenException이 발생한다")
        void 비작성자_호출_예외() {
            RoommateApplication application = application(ApplicationStatus.PENDING);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);

            Long otherUserId = 999L;
            assertThatThrownBy(() -> roommateApplicationUseCase.processApplication(
                    otherUserId, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.ACCEPTED)))
                    .isInstanceOf(ApplicationProcessForbiddenException.class);

            then(roommateGroupMemberCreateService).should(never()).addMember(any(), any(), any());
            then(chatSaveService).should(never()).save(any(), any(), any(), any());
        }

        @Test
        @DisplayName("이미 ACCEPTED 상태인 신청 처리 시 InvalidApplicationStatusException이 발생한다")
        void 이미_ACCEPTED_상태_예외() {
            RoommateApplication application = application(ApplicationStatus.ACCEPTED);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);

            assertThatThrownBy(() -> roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.REJECTED)))
                    .isInstanceOf(InvalidApplicationStatusException.class);

            then(roommateGroupMemberCreateService).should(never()).addMember(any(), any(), any());
            then(chatSaveService).should(never()).save(any(), any(), any(), any());
        }

        @Test
        @DisplayName("이미 REJECTED 상태인 신청 처리 시 InvalidApplicationStatusException이 발생한다")
        void 이미_REJECTED_상태_예외() {
            RoommateApplication application = application(ApplicationStatus.REJECTED);
            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);

            assertThatThrownBy(() -> roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.ACCEPTED)))
                    .isInstanceOf(InvalidApplicationStatusException.class);
        }

        @Test
        @DisplayName("그룹 MEMBER 정원이 가득 찬 상태에서 ACCEPTED 시 RecruitLimitReachedException이 발생한다")
        void 정원_초과_예외() {
            RoommateApplication application = application(ApplicationStatus.PENDING);
            RoommatePost post = application.getPost();
            RoommateGroup group = group(post);

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(roommateGroupGetService.getByPostIdForUpdate(POST_ID)).willReturn(group);
            given(roommateGroupMemberGetService.countByGroupIdAndRole(GROUP_ID, GroupMemberRole.MEMBER))
                    .willReturn((long) RECRUIT_MEMBER_COUNT);

            assertThatThrownBy(() -> roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.ACCEPTED)))
                    .isInstanceOf(RecruitLimitReachedException.class);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
            then(roommateGroupMemberCreateService).should(never()).addMember(any(), any(), any());
            then(chatSaveService).should(never()).save(any(), any(), any(), any());
        }

        @Test
        @DisplayName("ACCEPTED 처리 시 채팅방 카테고리가 APPLICATION에서 GENERAL로 변경된다")
        void ACCEPTED_처리_시_채팅방_카테고리_GENERAL_복원() {
            // given
            RoommateApplication application = application(ApplicationStatus.PENDING);
            RoommatePost post = application.getPost();
            RoommateGroup group = group(post);
            ChatRoom chatRoom = chatRoom();
            chatRoom.updateCategory(ChatRoomCategory.APPLICATION);

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(roommateGroupGetService.getByPostIdForUpdate(POST_ID)).willReturn(group);
            given(roommateGroupMemberGetService.countByGroupIdAndRole(GROUP_ID, GroupMemberRole.MEMBER)).willReturn(0L);
            given(roommateGroupMemberGetService.existsByUserIdAndRole(APPLICANT_ID, GroupMemberRole.MEMBER)).willReturn(false);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.of(chatRoom));
            stubChatSaved(chatRoom, ACCEPTED_MESSAGE, MessageType.APPLICATION_ACCEPTED);

            // when
            roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.ACCEPTED));

            // then
            org.mockito.Mockito.verify(chatStateUpdateService).updateCategory(chatRoom.getId(), ChatRoomCategory.GENERAL);
        }

        @Test
        @DisplayName("REJECTED 처리 시 채팅방 카테고리가 APPLICATION에서 GENERAL로 변경된다")
        void REJECTED_처리_시_채팅방_카테고리_GENERAL_복원() {
            // given
            RoommateApplication application = application(ApplicationStatus.PENDING);
            ChatRoom chatRoom = chatRoom();
            chatRoom.updateCategory(ChatRoomCategory.APPLICATION);

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.of(chatRoom));
            stubChatSaved(chatRoom, REJECTED_MESSAGE, MessageType.APPLICATION_REJECTED);

            // when
            roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.REJECTED));

            // then
            org.mockito.Mockito.verify(chatStateUpdateService).updateCategory(chatRoom.getId(), ChatRoomCategory.GENERAL);
        }

        @Test
        @DisplayName("신청자가 이미 다른 그룹의 MEMBER일 때 ACCEPTED 시 ApplicantAlreadyInGroupException이 발생한다")
        void 신청자_중복_소속_예외() {
            RoommateApplication application = application(ApplicationStatus.PENDING);
            RoommatePost post = application.getPost();
            RoommateGroup group = group(post);

            given(roommateApplicationGetService.getWithPostAndUserById(APPLICATION_ID)).willReturn(application);
            given(roommateGroupGetService.getByPostIdForUpdate(POST_ID)).willReturn(group);
            given(roommateGroupMemberGetService.countByGroupIdAndRole(GROUP_ID, GroupMemberRole.MEMBER)).willReturn(0L);
            given(roommateGroupMemberGetService.existsByUserIdAndRole(APPLICANT_ID, GroupMemberRole.MEMBER))
                    .willReturn(true);

            assertThatThrownBy(() -> roommateApplicationUseCase.processApplication(
                    AUTHOR_ID, APPLICATION_ID, new ProcessRoommateApplicationRequest(ApplicationStatus.ACCEPTED)))
                    .isInstanceOf(ApplicantAlreadyInGroupException.class);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
            then(roommateGroupMemberCreateService).should(never()).addMember(any(), any(), any());
            then(chatSaveService).should(never()).save(any(), any(), any(), any());
        }
    }
}
