package com.project.bangjjack.domain.application.application.usecase;

import com.project.bangjjack.domain.application.application.dto.request.ProcessRoommateApplicationRequest;
import com.project.bangjjack.domain.application.application.dto.response.CreateRoommateApplicationResponse;
import com.project.bangjjack.domain.application.application.dto.response.ProcessRoommateApplicationResponse;
import com.project.bangjjack.domain.application.application.exception.AlreadyAppliedPendingException;
import com.project.bangjjack.domain.application.application.exception.ApplicantAlreadyInGroupException;
import com.project.bangjjack.domain.application.application.exception.ApplicationCancelForbiddenException;
import com.project.bangjjack.domain.application.application.exception.ApplicationPreconditionNotMetException;
import com.project.bangjjack.domain.application.application.exception.ApplicationProcessForbiddenException;
import com.project.bangjjack.domain.application.application.exception.CannotApplyToOwnPostException;
import com.project.bangjjack.domain.application.application.exception.InvalidApplicationStatusException;
import com.project.bangjjack.domain.application.application.exception.InvalidTargetStatusException;
import com.project.bangjjack.domain.application.application.exception.NoOpenPostForUserException;
import com.project.bangjjack.domain.application.application.exception.OwnOpenPostExistsForApplicantException;
import com.project.bangjjack.domain.application.application.exception.RecruitLimitReachedException;
import com.project.bangjjack.domain.application.domain.entity.ApplicationStatus;
import com.project.bangjjack.domain.application.domain.entity.RoommateApplication;
import com.project.bangjjack.domain.application.domain.service.RoommateApplicationCreateService;
import com.project.bangjjack.domain.application.domain.service.RoommateApplicationGetService;
import com.project.bangjjack.domain.chat.application.dto.response.ChatMessageBroadcast;
import com.project.bangjjack.domain.chat.application.event.ChatMessageSentEvent;
import com.project.bangjjack.domain.chat.application.event.ChatRoomCreatedEvent;
import com.project.bangjjack.domain.chat.application.exception.ChatRoomNotFoundException;
import com.project.bangjjack.domain.chat.domain.entity.Chat;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoom;
import com.project.bangjjack.domain.chat.domain.entity.ChatRoomCategory;
import com.project.bangjjack.domain.chat.domain.entity.MessageType;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomCreateService;
import com.project.bangjjack.domain.chat.domain.service.ChatRoomGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatSaveService;
import com.project.bangjjack.domain.post.domain.entity.RoommatePost;
import com.project.bangjjack.domain.post.domain.service.RoommatePostGetService;
import com.project.bangjjack.domain.roommategroup.domain.entity.GroupMemberRole;
import com.project.bangjjack.domain.roommategroup.domain.entity.RoommateGroup;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupGetService;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupMemberCreateService;
import com.project.bangjjack.domain.roommategroup.domain.service.RoommateGroupMemberGetService;
import com.project.bangjjack.domain.user.domain.entity.User;
import com.project.bangjjack.domain.user.domain.service.UserGetService;
import com.project.bangjjack.domain.chat.domain.service.ChatStateUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoommateApplicationUseCase {

    private static final String APPLICATION_CHAT_MESSAGE = "룸메이트 신청을 보냈습니다.";
    private static final String APPLICATION_ACCEPTED_MESSAGE = "룸메이트 신청을 수락했습니다.";
    private static final String APPLICATION_REJECTED_MESSAGE = "룸메이트 신청을 거절했습니다.";
    private static final String APPLICATION_CANCELED_MESSAGE = "룸메이트 신청을 취소했습니다.";

    private final UserGetService userGetService;
    private final RoommatePostGetService roommatePostGetService;
    private final RoommateApplicationGetService roommateApplicationGetService;
    private final RoommateApplicationCreateService roommateApplicationCreateService;
    private final ChatRoomGetService chatRoomGetService;
    private final ChatRoomCreateService chatRoomCreateService;
    private final ChatSaveService chatSaveService;
    private final ChatStateUpdateService chatStateUpdateService;
    private final RoommateGroupGetService roommateGroupGetService;
    private final RoommateGroupMemberGetService roommateGroupMemberGetService;
    private final RoommateGroupMemberCreateService roommateGroupMemberCreateService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CreateRoommateApplicationResponse createApplication(Long userId, Long targetUserId) {
        if (targetUserId.equals(userId)) {
            throw new CannotApplyToOwnPostException();
        }

        User applicant = userGetService.getById(userId);

        if (!applicant.isOnboarded() || !applicant.isChecklistRegistered() || !applicant.isRoommatePreferenceRegistered()) {
            throw new ApplicationPreconditionNotMetException();
        }

        // 동시 중복 신청 방지: 대상 사용자의 OPEN 모집글 행을 잠가 존재 검사~생성 구간을 직렬화
        RoommatePost post = roommatePostGetService.findOpenWithUserByUserIdForUpdate(targetUserId)
                .orElseThrow(NoOpenPostForUserException::new);

        Long authorId = post.getUser().getId();
        Long postId = post.getId();

        if (roommatePostGetService.existsOpenPostByUser(applicant)) {
            throw new OwnOpenPostExistsForApplicantException();
        }

        if (roommateApplicationGetService.existsPendingByPostIdAndApplicantId(postId, userId)) {
            throw new AlreadyAppliedPendingException();
        }

        if (roommateGroupMemberGetService.existsByUserIdAndRole(userId, GroupMemberRole.MEMBER)) {
            throw new ApplicantAlreadyInGroupException();
        }

        String directRoomKey = chatRoomCreateService.createDirectKey(userId, authorId);
        Optional<ChatRoom> existing = chatRoomGetService.findByDirectRoomKey(directRoomKey);
        boolean isNewChatRoom = existing.isEmpty();
        ChatRoom chatRoom = existing.orElseGet(
                () -> chatRoomCreateService.createDirectRoom(userId, authorId, directRoomKey));

        if (isNewChatRoom) {
            eventPublisher.publishEvent(new ChatRoomCreatedEvent(chatRoom.getId(), List.of(userId, authorId)));
        }

        RoommateApplication saved = roommateApplicationCreateService.createApplication(
                RoommateApplication.create(post, applicant)
        );

        Chat chat = chatSaveService.save(userId, chatRoom, APPLICATION_CHAT_MESSAGE, MessageType.APPLICATION_SENT, saved.getId());
        chatStateUpdateService.updateCategory(chatRoom.getId(), ChatRoomCategory.APPLICATION);
        eventPublisher.publishEvent(new ChatMessageSentEvent(
                chatRoom.getId(), ChatMessageBroadcast.from(chat, chatRoom.getId())));

        return CreateRoommateApplicationResponse.from(saved, chatRoom.getId(), isNewChatRoom);
    }

    @Transactional
    public ProcessRoommateApplicationResponse processApplication(
            Long userId,
            Long applicationId,
            ProcessRoommateApplicationRequest request
    ) {
        ApplicationStatus targetStatus = request.status();
        if (targetStatus == ApplicationStatus.PENDING) {
            throw new InvalidTargetStatusException();
        }

        RoommateApplication application = roommateApplicationGetService.getWithPostAndUserById(applicationId);

        RoommatePost post = application.getPost();
        Long authorId = post.getUser().getId();
        if (!authorId.equals(userId)) {
            throw new ApplicationProcessForbiddenException();
        }

        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new InvalidApplicationStatusException();
        }

        User applicant = application.getApplicant();
        Long applicantId = applicant.getId();

        RoommateGroup group = null;
        Integer currentMemberCount = null;

        if (targetStatus == ApplicationStatus.ACCEPTED) {
            // 동시 수락 정원 초과 방지: group 행을 잠가 카운트~멤버추가 구간을 직렬화
            group = roommateGroupGetService.getByPostIdForUpdate(post.getId());

            long memberCount = roommateGroupMemberGetService.countByGroupIdAndRole(group.getId(), GroupMemberRole.MEMBER);
            if (memberCount >= post.getRecruitMemberCount()) {
                throw new RecruitLimitReachedException();
            }

            if (roommateGroupMemberGetService.existsByUserIdAndRole(applicantId, GroupMemberRole.MEMBER)) {
                throw new ApplicantAlreadyInGroupException();
            }

            application.accept();
            roommateGroupMemberCreateService.addMember(group, applicant, GroupMemberRole.MEMBER);
            currentMemberCount = (int) (memberCount + 1);
        } else {
            application.reject();
        }

        String directRoomKey = chatRoomCreateService.createDirectKey(applicantId, authorId);
        ChatRoom chatRoom = chatRoomGetService.findByDirectRoomKey(directRoomKey)
                .orElseThrow(ChatRoomNotFoundException::new);

        boolean accepted = targetStatus == ApplicationStatus.ACCEPTED;
        String content = accepted ? APPLICATION_ACCEPTED_MESSAGE : APPLICATION_REJECTED_MESSAGE;
        MessageType messageType = accepted ? MessageType.APPLICATION_ACCEPTED : MessageType.APPLICATION_REJECTED;
        Chat chat = chatSaveService.save(authorId, chatRoom, content, messageType, applicationId);
        chatStateUpdateService.updateCategory(chatRoom.getId(), ChatRoomCategory.GENERAL);
        eventPublisher.publishEvent(new ChatMessageSentEvent(
                chatRoom.getId(), ChatMessageBroadcast.from(chat, chatRoom.getId())));

        Long groupId = group != null ? group.getId() : null;
        return ProcessRoommateApplicationResponse.from(application, chatRoom.getId(), groupId, currentMemberCount);
    }

    @Transactional
    public void cancelApplication(Long userId, Long applicationId) {
        RoommateApplication application = roommateApplicationGetService.getWithPostAndUserById(applicationId);

        Long applicantId = application.getApplicant().getId();
        if (!applicantId.equals(userId)) {
            throw new ApplicationCancelForbiddenException();
        }

        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new InvalidApplicationStatusException();
        }

        application.cancel();

        Long authorId = application.getPost().getUser().getId();
        String directRoomKey = chatRoomCreateService.createDirectKey(applicantId, authorId);
        ChatRoom chatRoom = chatRoomGetService.findByDirectRoomKey(directRoomKey)
                .orElseThrow(ChatRoomNotFoundException::new);

        Chat chat = chatSaveService.save(
                applicantId, chatRoom, APPLICATION_CANCELED_MESSAGE, MessageType.APPLICATION_CANCELED, applicationId);
        chatStateUpdateService.updateCategory(chatRoom.getId(), ChatRoomCategory.GENERAL);
        eventPublisher.publishEvent(new ChatMessageSentEvent(
                chatRoom.getId(), ChatMessageBroadcast.from(chat, chatRoom.getId())));
    }
}
