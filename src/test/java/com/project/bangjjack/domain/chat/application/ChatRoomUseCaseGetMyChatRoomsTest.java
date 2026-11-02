package com.project.bangjjack.domain.chat.application;

import com.project.bangjjack.domain.chat.application.dto.response.ChatRoomListResponse;
import com.project.bangjjack.domain.chat.application.usecase.ChatRoomUseCase;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomParticipant;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.service.ChatMessageGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomCreateService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.user.domain.entity.User;
import com.project.bangjjack.domain.user.domain.service.UserGetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoomUseCase.getMyChatRooms")
class ChatRoomUseCaseGetMyChatRoomsTest {

    @Mock private ChatRoomCreateService chatRoomCreateService;
    @Mock private ChatRoomGetService chatRoomGetService;
    @Mock private ChatMessageGetService chatMessageGetService;
    @Mock private UserGetService userGetService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ChatRoomUseCase chatRoomUseCase;

    private static final Long USER_ID = 1L;
    private static final Long PARTNER_ID = 2L;

    private ChatRoom createRoomWithId(Long id) throws Exception {
        ChatRoom room = ChatRoom.createDirect(USER_ID, "DM_1_2");
        setField(room, "id", id, room.getClass().getSuperclass());
        return room;
    }

    private ChatRoom createRoomWithIdAndTime(Long id, LocalDateTime lastMessageAt) throws Exception {
        ChatRoom room = createRoomWithId(id);
        setField(room, "lastMessageAt", lastMessageAt, room.getClass());
        return room;
    }

    private ChatRoomParticipant createParticipant(ChatRoom room, Long userId, long unreadCount) throws Exception {
        ChatRoomParticipant p = ChatRoomParticipant.create(room, userId);
        setField(p, "unreadCount", unreadCount, p.getClass());
        return p;
    }

    private User createUser(Long id, String username) throws Exception {
        User user = User.create("pid_" + id, username, username + "@test.com", null);
        setField(user, "id", id, user.getClass().getSuperclass());
        return user;
    }

    private void setField(Object target, String fieldName, Object value, Class<?> clazz) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Nested
    @DisplayName("참여 채팅방 없음")
    class WhenNoChatRooms {

        @Test
        @DisplayName("빈 리스트 반환")
        void 빈_리스트_반환() {
            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 20, null)).willReturn(List.of());

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, null, null, 20);

            assertThat(response.rooms()).isEmpty();
            assertThat(response.hasNext()).isFalse();
            assertThat(response.nextCursor()).isNull();
        }
    }

    @Nested
    @DisplayName("채팅방 1개")
    class WhenOneChatRoom {

        @Test
        @DisplayName("partner, lastMessage, unreadCount 정상 조립")
        void 정상_조립() throws Exception {
            ChatRoom room = createRoomWithIdAndTime(10L, LocalDateTime.now());
            ChatRoomParticipant myParticipant = createParticipant(room, USER_ID, 3L);
            User partner = createUser(PARTNER_ID, "파트너");
            Chat lastChat = Chat.create(USER_ID, room, "안녕하세요", MessageType.USER, 1L);

            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 20, null))
                    .willReturn(List.of(myParticipant));
            given(chatRoomGetService.findPartnerIdsByDirectRoomIds(any(), eq(USER_ID)))
                    .willReturn(Map.of(10L, PARTNER_ID));
            given(userGetService.getByIds(any())).willReturn(Map.of(PARTNER_ID, partner));
            given(chatMessageGetService.findLastMessagesByRoomIds(any())).willReturn(Map.of(10L, lastChat));

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, null, null, 20);

            assertThat(response.rooms()).hasSize(1);
            assertThat(response.rooms().get(0).partnerId()).isEqualTo(PARTNER_ID);
            assertThat(response.rooms().get(0).partnerName()).isEqualTo("파트너");
            assertThat(response.rooms().get(0).lastMessage()).isEqualTo("안녕하세요");
            assertThat(response.rooms().get(0).unreadCount()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("메시지 없는 방")
    class WhenNoMessages {

        @Test
        @DisplayName("lastMessage=null, unreadCount=0")
        void 메시지_없는_방() throws Exception {
            ChatRoom room = createRoomWithId(10L);
            ChatRoomParticipant myParticipant = createParticipant(room, USER_ID, 0L);
            User partner = createUser(PARTNER_ID, "파트너");

            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 20, null))
                    .willReturn(List.of(myParticipant));
            given(chatRoomGetService.findPartnerIdsByDirectRoomIds(any(), eq(USER_ID)))
                    .willReturn(Map.of(10L, PARTNER_ID));
            given(userGetService.getByIds(any())).willReturn(Map.of(PARTNER_ID, partner));
            given(chatMessageGetService.findLastMessagesByRoomIds(any())).willReturn(Map.of());

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, null, null, 20);

            assertThat(response.rooms().get(0).lastMessage()).isNull();
            assertThat(response.rooms().get(0).unreadCount()).isZero();
        }
    }

    @Nested
    @DisplayName("정렬")
    class Sorting {

        @Test
        @DisplayName("DB에서 정렬된 순서 그대로 응답에 반영")
        void DB_정렬_순서_유지() throws Exception {
            ChatRoom roomWithMsg = createRoomWithIdAndTime(10L, LocalDateTime.now().minusMinutes(5));
            ChatRoom roomNoMsg = createRoomWithId(20L);
            User partner = createUser(PARTNER_ID, "파트너");

            // DB ORDER BY 결과를 모사: lastMessageAt 있는 방이 먼저
            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 20, null))
                    .willReturn(List.of(
                            createParticipant(roomWithMsg, USER_ID, 0L),
                            createParticipant(roomNoMsg, USER_ID, 0L)
                    ));
            given(chatRoomGetService.findPartnerIdsByDirectRoomIds(any(), eq(USER_ID)))
                    .willReturn(Map.of(10L, PARTNER_ID, 20L, PARTNER_ID));
            given(userGetService.getByIds(any())).willReturn(Map.of(PARTNER_ID, partner));
            given(chatMessageGetService.findLastMessagesByRoomIds(any())).willReturn(Map.of());

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, null, null, 20);

            assertThat(response.rooms()).hasSize(2);
            assertThat(response.rooms().get(0).roomId()).isEqualTo(10L);
            assertThat(response.rooms().get(1).roomId()).isEqualTo(20L);
        }
    }

    @Nested
    @DisplayName("APPLICATION 필터")
    class ApplicationFilter {

        @Test
        @DisplayName("APPLICATION 필터 → 해당 방만 반환")
        void APPLICATION_필터_해당_방만_반환() throws Exception {
            ChatRoom appRoom = createRoomWithIdAndTime(10L, LocalDateTime.now());
            setField(appRoom, "category", ChatRoomCategory.APPLICATION, appRoom.getClass());
            User partner = createUser(PARTNER_ID, "파트너");

            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 20, ChatRoomCategory.APPLICATION))
                    .willReturn(List.of(createParticipant(appRoom, USER_ID, 0L)));
            given(chatRoomGetService.findPartnerIdsByDirectRoomIds(any(), eq(USER_ID)))
                    .willReturn(Map.of(10L, PARTNER_ID));
            given(userGetService.getByIds(any())).willReturn(Map.of(PARTNER_ID, partner));
            given(chatMessageGetService.findLastMessagesByRoomIds(any())).willReturn(Map.of());

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, ChatRoomCategory.APPLICATION, null, 20);

            assertThat(response.rooms()).hasSize(1);
        }

        @Test
        @DisplayName("APPLICATION 필터 + 해당 방 없음 → 빈 리스트")
        void APPLICATION_필터_방_없으면_빈_리스트() {
            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 20, ChatRoomCategory.APPLICATION))
                    .willReturn(List.of());

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, ChatRoomCategory.APPLICATION, null, 20);

            assertThat(response.rooms()).isEmpty();
        }

        @Test
        @DisplayName("APPLICATION 필터 + hasNext=true → nextCursor 설정")
        void APPLICATION_필터_hasNext_true이면_nextCursor_설정() throws Exception {
            ChatRoom room1 = createRoomWithIdAndTime(10L, LocalDateTime.now().minusMinutes(1));
            ChatRoom room2 = createRoomWithIdAndTime(20L, LocalDateTime.now().minusMinutes(2));
            User partner = createUser(PARTNER_ID, "파트너");

            // size=1이므로 size+1=2개 반환 → hasNext=true
            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 1, ChatRoomCategory.APPLICATION))
                    .willReturn(List.of(
                            createParticipant(room1, USER_ID, 0L),
                            createParticipant(room2, USER_ID, 0L)
                    ));
            given(chatRoomGetService.findPartnerIdsByDirectRoomIds(any(), eq(USER_ID)))
                    .willReturn(Map.of(10L, PARTNER_ID));
            given(userGetService.getByIds(any())).willReturn(Map.of(PARTNER_ID, partner));
            given(chatMessageGetService.findLastMessagesByRoomIds(any())).willReturn(Map.of());

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, ChatRoomCategory.APPLICATION, null, 1);

            assertThat(response.hasNext()).isTrue();
            assertThat(response.nextCursor()).isNotNull();
        }
    }

    @Nested
    @DisplayName("파트너가 채팅방을 나간 경우")
    class PartnerLeft {

        @Test
        @DisplayName("파트너 정보가 없어도 채팅방이 목록에 포함되며 partner 필드는 null")
        void 파트너가_나가도_목록에_포함() throws Exception {
            ChatRoom room = createRoomWithId(10L);

            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 20, null))
                    .willReturn(List.of(createParticipant(room, USER_ID, 0L)));
            given(chatRoomGetService.findPartnerIdsByDirectRoomIds(any(), eq(USER_ID)))
                    .willReturn(Map.of()); // 파트너가 나가서 조회 안 됨
            given(userGetService.getByIds(any())).willReturn(Map.of());
            given(chatMessageGetService.findLastMessagesByRoomIds(any())).willReturn(Map.of());

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, null, null, 20);

            assertThat(response.rooms()).hasSize(1);
            assertThat(response.rooms().get(0).partnerId()).isNull();
            assertThat(response.rooms().get(0).partnerName()).isNull();
        }
    }

    @Nested
    @DisplayName("CLOSED 상태 채팅방")
    class ClosedChatRoom {

        @Test
        @DisplayName("CLOSED 상태 채팅방도 목록에 포함")
        void CLOSED_방도_목록에_포함() throws Exception {
            ChatRoom room = createRoomWithId(10L);
            room.close();
            User partner = createUser(PARTNER_ID, "파트너");

            given(chatRoomGetService.findMyDirectParticipantsPage(USER_ID, null, null, 20, null))
                    .willReturn(List.of(createParticipant(room, USER_ID, 0L)));
            given(chatRoomGetService.findPartnerIdsByDirectRoomIds(any(), eq(USER_ID)))
                    .willReturn(Map.of(10L, PARTNER_ID));
            given(userGetService.getByIds(any())).willReturn(Map.of(PARTNER_ID, partner));
            given(chatMessageGetService.findLastMessagesByRoomIds(any())).willReturn(Map.of());

            ChatRoomListResponse response = chatRoomUseCase.getMyChatRooms(USER_ID, null, null, 20);

            assertThat(response.rooms()).hasSize(1);
        }
    }
}
