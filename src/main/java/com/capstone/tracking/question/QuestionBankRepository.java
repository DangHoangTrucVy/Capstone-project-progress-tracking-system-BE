package com.capstone.tracking.question;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QuestionBankRepository extends JpaRepository<QuestionBankItem, UUID> {

    // createdBy is read by QuestionResponse after the transaction closes (open-in-view is off).
    @EntityGraph(attributePaths = "createdBy")
    Page<QuestionBankItem> findByTopicId(UUID topicId, Pageable pageable);

    @EntityGraph(attributePaths = "createdBy")
    Page<QuestionBankItem> findByTopicIdAndCategoryIgnoreCase(UUID topicId, String category, Pageable pageable);
}
