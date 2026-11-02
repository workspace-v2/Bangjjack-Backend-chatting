package com.project.bangjjack.domain.application.application.usecase;

import com.project.bangjjack.domain.application.application.dto.response.CreateRoommateApplicationResponse;
import com.project.bangjjack.domain.application.application.exception.AlreadyAppliedPendingException;
import com.project.bangjjack.domain.application.application.exception.ApplicantAlreadyInGroupException;
import com.project.bangjjack.domain.application.application.exception.ApplicationPreconditionNotMetException;
import com.project.bangjjack.domain.application.application.exception.CannotApplyToOwnPostException;
import com.project.bangjjack.domain.application.application.exception.NoOpenPostForUserException;
import com.project.bangjjack.domain.application.application.exception.OwnOpenPostExistsForApplicantException;
import com.project.bangjjack.domain.application.domain.entity.ApplicationStatus;
import com.project.bangjjack.domain.application.domain.entity.RoommateApplication;
import com.project.bangjjack.domain.application.domain.service.RoommateApplicationCreateService;
import com.project.bangjjack.domain.application.domain.service.RoommateApplicationGetService;
import com.project.bangjjack.domain.chat.application.event.ChatMessageSentEvent;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomCreateService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
import com.project.bangjjack.domain.post.domain.entity.RoomSize;
import com.project.bangjjack.domain.post.domain.entity.RoommatePost;
import com.project.bangjjack.domain.post.domain.service.RoommatePostGetService;
import com.project.bangjjack.domain.roommategroup.domain.entity.GroupMemberRole;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RoommateApplicationUseCaseCreateTest {

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
    private RoommateGroupMemberGetService roommateGroupMemberGetService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock private ChatStateUpdateService chatStateUpdateService;

    @InjectMocks
    private RoommateApplicationUseCase roommateApplicationUseCase;

    private static final Long APPLICANT_ID = 1L;
    private static final Long AUTHOR_ID = 2L;
    private static final Long POST_ID = 100L;
    private static final Long CHAT_ROOM_ID = 500L;
    private static final Long APPLICATION_ID = 999L;
    private static final Long CHAT_ID = 7777L;
    private static final String APPLICATION_CHAT_MESSAGE = "룸메이트 신청을 보냈습니다.";

    private User fullyRegisteredUser() {
        User user = User.create("provider-applicant", "신청자", "applicant@gachon.ac.kr", null);
        user.completeOnboarding(2000, 2, Gender.MALE, Campus.GLOBAL_CAMPUS, null, Semester.SIXTEEN_WEEKS, Dormitory.DORM_1);
        user.completeChecklistRegistration();
        user.completeRoommatePreferenceRegistration();
        return user;
    }

    private User author() {
        User user = User.create("provider-author", "작성자", "author@gachon.ac.kr", null);
        user.completeOnboarding(2000, 2, Gender.FEMALE, Campus.GLOBAL_CAMPUS, null, Semester.SIXTEEN_WEEKS, Dormitory.DORM_2);
        user.completeChecklistRegistration();
        user.completeRoommatePreferenceRegistration();
        ReflectionTestUtils.setField(user, "id", AUTHOR_ID);
        return user;
    }

    private RoommatePost openPost(User author) {
        RoommatePost post = RoommatePost.create(
                author, "제목", "내용",
                RoomSize.TWO_PERSON, 1,
                Semester.SIXTEEN_WEEKS, Dormitory.DORM_2);
        ReflectionTestUtils.setField(post, "id", POST_ID);
        return post;
    }

    private ChatRoom chatRoom() {
        ChatRoom room = ChatRoom.createDirect(APPLICANT_ID, ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
        ReflectionTestUtils.setField(room, "id", CHAT_ROOM_ID);
        return room;
    }

    private void stubApplicantSaved() {
        given(roommateApplicationCreateService.createApplication(any(RoommateApplication.class)))
                .willAnswer(invocation -> {
                    RoommateApplication arg = invocation.getArgument(0);
                    ReflectionTestUtils.setField(arg, "id", APPLICATION_ID);
                    return arg;
                });
    }

    private void stubChatSaved(ChatRoom room) {
        given(chatSaveService.save(eq(APPLICANT_ID), eq(room), eq(APPLICATION_CHAT_MESSAGE), eq(MessageType.APPLICATION_SENT), eq(APPLICATION_ID)))
                .willAnswer(invocation -> {
                    Chat chat = Chat.create(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), MessageType.APPLICATION_SENT, APPLICATION_ID);
                    ReflectionTestUtils.setField(chat, "id", CHAT_ID);
                    return chat;
                });
    }

    @Nested
    @DisplayName("룸메이트 신청 생성 시")
    class CreateApplication {

        @Test
        @DisplayName("모든 사전 조건 충족 + 채팅방 미존재 시 신청과 1:1 채팅방이 함께 생성된다")
        void 채팅방_미존재_시_신규생성_및_신청생성_성공() {
            // given
            User applicant = fullyRegisteredUser();
            ReflectionTestUtils.setField(applicant, "id", APPLICANT_ID);
            User author = author();
            RoommatePost post = openPost(author);
            ChatRoom newRoom = chatRoom();

            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);
            given(roommatePostGetService.findOpenWithUserByUserIdForUpdate(AUTHOR_ID)).willReturn(Optional.of(post));
            given(roommatePostGetService.existsOpenPostByUser(applicant)).willReturn(false);
            given(roommateApplicationGetService.existsPendingByPostIdAndApplicantId(POST_ID, APPLICANT_ID)).willReturn(false);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.empty());
            given(chatRoomCreateService.createDirectRoom(eq(APPLICANT_ID), eq(AUTHOR_ID), anyString()))
                    .willReturn(newRoom);
            stubApplicantSaved();
            stubChatSaved(newRoom);

            // when
            CreateRoommateApplicationResponse response =
                    roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID);

            // then
            assertThat(response.applicationId()).isEqualTo(APPLICATION_ID);
            assertThat(response.postId()).isEqualTo(POST_ID);
            assertThat(response.applicantId()).isEqualTo(APPLICANT_ID);
            assertThat(response.status()).isEqualTo(ApplicationStatus.PENDING);
            assertThat(response.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(response.isNewChatRoom()).isTrue();
            then(chatRoomCreateService).should().createDirectRoom(eq(APPLICANT_ID), eq(AUTHOR_ID), anyString());
            then(chatSaveService).should().save(APPLICANT_ID, newRoom, APPLICATION_CHAT_MESSAGE, MessageType.APPLICATION_SENT, APPLICATION_ID);
        }

        @Test
        @DisplayName("채팅방이 이미 존재하는 경우 기존 채팅방을 재사용하고 isNewChatRoom=false로 반환한다")
        void 채팅방_존재_시_재사용() {
            // given
            User applicant = fullyRegisteredUser();
            ReflectionTestUtils.setField(applicant, "id", APPLICANT_ID);
            User author = author();
            RoommatePost post = openPost(author);
            ChatRoom existingRoom = chatRoom();

            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);
            given(roommatePostGetService.findOpenWithUserByUserIdForUpdate(AUTHOR_ID)).willReturn(Optional.of(post));
            given(roommatePostGetService.existsOpenPostByUser(applicant)).willReturn(false);
            given(roommateApplicationGetService.existsPendingByPostIdAndApplicantId(POST_ID, APPLICANT_ID)).willReturn(false);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.of(existingRoom));
            stubApplicantSaved();
            stubChatSaved(existingRoom);

            // when
            CreateRoommateApplicationResponse response =
                    roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID);

            // then
            assertThat(response.isNewChatRoom()).isFalse();
            assertThat(response.chatRoomId()).isEqualTo(CHAT_ROOM_ID);
            then(chatRoomCreateService).should(never()).createDirectRoom(anyLong(), anyLong(), anyString());
            then(chatSaveService).should().save(APPLICANT_ID, existingRoom, APPLICATION_CHAT_MESSAGE, MessageType.APPLICATION_SENT, APPLICATION_ID);
        }

        @Test
        @DisplayName("신청 생성 시 신청자 명의로 고정 문구 채팅이 저장되고 broadcast 이벤트가 발행된다")
        void 신청_채팅_저장_및_브로드캐스트_이벤트_발행() {
            // given
            User applicant = fullyRegisteredUser();
            ReflectionTestUtils.setField(applicant, "id", APPLICANT_ID);
            User author = author();
            RoommatePost post = openPost(author);
            ChatRoom newRoom = chatRoom();

            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);
            given(roommatePostGetService.findOpenWithUserByUserIdForUpdate(AUTHOR_ID)).willReturn(Optional.of(post));
            given(roommatePostGetService.existsOpenPostByUser(applicant)).willReturn(false);
            given(roommateApplicationGetService.existsPendingByPostIdAndApplicantId(POST_ID, APPLICANT_ID)).willReturn(false);
            given(chatRoomCreateService.createDirectKey(APPLICANT_ID, AUTHOR_ID))
                    .willReturn(ChatRoom.generateDirectKey(APPLICANT_ID, AUTHOR_ID));
            given(chatRoomGetService.findByDirectRoomKey(anyString())).willReturn(Optional.empty());
            given(chatRoomCreateService.createDirectRoom(eq(APPLICANT_ID), eq(AUTHOR_ID), anyString()))
                    .willReturn(newRoom);
            stubApplicantSaved();
            stubChatSaved(newRoom);

            // when
            roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID);

            // then
            then(chatSaveService).should().save(APPLICANT_ID, newRoom, APPLICATION_CHAT_MESSAGE, MessageType.APPLICATION_SENT, APPLICATION_ID);

            ArgumentCaptor<ChatMessageSentEvent> captor = ArgumentCaptor.forClass(ChatMessageSentEvent.class);
            then(eventPublisher).should().publishEvent(captor.capture());
            ChatMessageSentEvent event = captor.getValue();
            assertThat(event.roomId()).isEqualTo(CHAT_ROOM_ID);
            assertThat(event.broadcast().messageId()).isEqualTo(CHAT_ID);
            assertThat(event.broadcast().senderId()).isEqualTo(APPLICANT_ID);
            assertThat(event.broadcast().content()).isEqualTo(APPLICATION_CHAT_MESSAGE);
        }

        @Test
        @DisplayName("대상 사용자가 본인이면 CannotApplyToOwnPostException이 발생한다")
        void 자기_자신_대상_신청_예외() {
            assertThatThrownBy(() -> roommateApplicationUseCase.createApplication(APPLICANT_ID, APPLICANT_ID))
                    .isInstanceOf(CannotApplyToOwnPostException.class);

            then(roommateApplicationCreateService).should(never()).createApplication(any());
        }

        @Test
        @DisplayName("온보딩 미완료 사용자가 신청하면 ApplicationPreconditionNotMetException이 발생한다")
        void 온보딩_미완료_사용자_예외() {
            User applicant = User.create("provider", "신청자", "a@gachon.ac.kr", null);
            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);

            assertThatThrownBy(() -> roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID))
                    .isInstanceOf(ApplicationPreconditionNotMetException.class);

            then(roommateApplicationCreateService).should(never()).createApplication(any());
        }

        @Test
        @DisplayName("체크리스트 미등록 사용자가 신청하면 ApplicationPreconditionNotMetException이 발생한다")
        void 체크리스트_미등록_사용자_예외() {
            User applicant = User.create("provider", "신청자", "a@gachon.ac.kr", null);
            applicant.completeOnboarding(2000, 2, Gender.MALE, Campus.GLOBAL_CAMPUS, null, Semester.SIXTEEN_WEEKS, Dormitory.DORM_1);
            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);

            assertThatThrownBy(() -> roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID))
                    .isInstanceOf(ApplicationPreconditionNotMetException.class);
        }

        @Test
        @DisplayName("선호도 미등록 사용자가 신청하면 ApplicationPreconditionNotMetException이 발생한다")
        void 선호도_미등록_사용자_예외() {
            User applicant = User.create("provider", "신청자", "a@gachon.ac.kr", null);
            applicant.completeOnboarding(2000, 2, Gender.MALE, Campus.GLOBAL_CAMPUS, null, Semester.SIXTEEN_WEEKS, Dormitory.DORM_1);
            applicant.completeChecklistRegistration();
            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);

            assertThatThrownBy(() -> roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID))
                    .isInstanceOf(ApplicationPreconditionNotMetException.class);
        }

        @Test
        @DisplayName("대상 사용자에게 OPEN 모집글이 없으면 NoOpenPostForUserException이 발생한다")
        void 대상_사용자_OPEN_모집글_없음_예외() {
            User applicant = fullyRegisteredUser();
            ReflectionTestUtils.setField(applicant, "id", APPLICANT_ID);

            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);
            given(roommatePostGetService.findOpenWithUserByUserIdForUpdate(AUTHOR_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID))
                    .isInstanceOf(NoOpenPostForUserException.class);

            then(roommateApplicationCreateService).should(never()).createApplication(any());
        }

        @Test
        @DisplayName("신청자가 본인의 OPEN 모집글을 보유한 작성자이면 OwnOpenPostExistsForApplicantException이 발생한다")
        void 신청자_OPEN_모집글_보유_시_예외() {
            User applicant = fullyRegisteredUser();
            ReflectionTestUtils.setField(applicant, "id", APPLICANT_ID);
            User author = author();
            RoommatePost post = openPost(author);

            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);
            given(roommatePostGetService.findOpenWithUserByUserIdForUpdate(AUTHOR_ID)).willReturn(Optional.of(post));
            given(roommatePostGetService.existsOpenPostByUser(applicant)).willReturn(true);

            assertThatThrownBy(() -> roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID))
                    .isInstanceOf(OwnOpenPostExistsForApplicantException.class);
        }

        @Test
        @DisplayName("동일 모집글에 PENDING 신청이 이미 존재하면 AlreadyAppliedPendingException이 발생한다")
        void PENDING_중복_신청_예외() {
            User applicant = fullyRegisteredUser();
            ReflectionTestUtils.setField(applicant, "id", APPLICANT_ID);
            User author = author();
            RoommatePost post = openPost(author);

            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);
            given(roommatePostGetService.findOpenWithUserByUserIdForUpdate(AUTHOR_ID)).willReturn(Optional.of(post));
            given(roommatePostGetService.existsOpenPostByUser(applicant)).willReturn(false);
            given(roommateApplicationGetService.existsPendingByPostIdAndApplicantId(POST_ID, APPLICANT_ID)).willReturn(true);

            assertThatThrownBy(() -> roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID))
                    .isInstanceOf(AlreadyAppliedPendingException.class);

            then(roommateApplicationCreateService).should(never()).createApplication(any());
        }

        @Test
        @DisplayName("신청자가 이미 다른 RoommateGroup의 MEMBER 역할이면 ApplicantAlreadyInGroupException이 발생한다")
        void 신청자_다른_그룹_MEMBER_보유_시_예외() {
            User applicant = fullyRegisteredUser();
            ReflectionTestUtils.setField(applicant, "id", APPLICANT_ID);
            User author = author();
            RoommatePost post = openPost(author);

            given(userGetService.getById(APPLICANT_ID)).willReturn(applicant);
            given(roommatePostGetService.findOpenWithUserByUserIdForUpdate(AUTHOR_ID)).willReturn(Optional.of(post));
            given(roommatePostGetService.existsOpenPostByUser(applicant)).willReturn(false);
            given(roommateApplicationGetService.existsPendingByPostIdAndApplicantId(POST_ID, APPLICANT_ID)).willReturn(false);
            given(roommateGroupMemberGetService.existsByUserIdAndRole(APPLICANT_ID, GroupMemberRole.MEMBER)).willReturn(true);

            assertThatThrownBy(() -> roommateApplicationUseCase.createApplication(APPLICANT_ID, AUTHOR_ID))
                    .isInstanceOf(ApplicantAlreadyInGroupException.class);

            then(roommateApplicationCreateService).should(never()).createApplication(any());
            then(chatSaveService).should(never()).save(any(), any(), any(), any());
        }
    }
}
