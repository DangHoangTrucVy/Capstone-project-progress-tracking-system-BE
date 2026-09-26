package com.capstone.tracking.question;

import com.capstone.tracking.common.exception.ResourceNotFoundException;
import com.capstone.tracking.group.GroupMemberRepository;
import com.capstone.tracking.group.MemberStatus;
import com.capstone.tracking.question.dto.QuestionCreateRequest;
import com.capstone.tracking.topic.Topic;
import com.capstone.tracking.topic.TopicService;
import com.capstone.tracking.user.Role;
import com.capstone.tracking.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuestionBankService {

    private final QuestionBankRepository questionBankRepository;
    private final TopicService topicService;
    private final GroupMemberRepository groupMemberRepository;

    /**
     * Admin adds to any topic's bank. A Group Leader may only send questions for the topic their own
     * group is working on, so they reach that group's supervisor (who browses the topic's bank);
     * either way the item starts as DRAFT.
     */
    @Transactional
    public QuestionBankItem create(UUID topicId, QuestionCreateRequest request, User createdBy) {
        Topic topic = topicService.getById(topicId);
        if (createdBy.getRole() == Role.GROUP_LEADER
                && !groupMemberRepository.existsByUserIdAndStatusAndGroup_Topic_Id(createdBy.getId(), MemberStatus.ACTIVE, topicId)) {
            throw new AccessDeniedException("Group Leaders can only send questions for their own group's topic");
        }
        QuestionBankItem item = QuestionBankItem.builder()
                .topic(topic)
                .category(request.category())
                .questionText(request.questionText())
                .guidanceNotes(request.guidanceNotes())
                .createdBy(createdBy)
                .status(QuestionStatus.DRAFT)
                .build();
        return questionBankRepository.save(item);
    }

    /** Matches API-006: GET /api/v1/topics/{id}/questions?category=... */
    public Page<QuestionBankItem> listByTopic(UUID topicId, String category, Pageable pageable) {
        // Ensures a 404 instead of a silently-empty page when the topic itself doesn't exist.
        if (!topicExists(topicId)) {
            throw ResourceNotFoundException.of("Topic", topicId);
        }
        return StringUtils.hasText(category)
                ? questionBankRepository.findByTopicIdAndCategoryIgnoreCase(topicId, category, pageable)
                : questionBankRepository.findByTopicId(topicId, pageable);
    }

    private boolean topicExists(UUID topicId) {
        try {
            topicService.getById(topicId);
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }
}
