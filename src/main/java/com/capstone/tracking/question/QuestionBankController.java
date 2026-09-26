package com.capstone.tracking.question;

import com.capstone.tracking.question.dto.QuestionCreateRequest;
import com.capstone.tracking.question.dto.QuestionResponse;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** FR-005 / API-006: question bank scoped to a Topic. */
@RestController
@RequestMapping("/api/v1/topics/{topicId}/questions")
@RequiredArgsConstructor
@Tag(name = "Question Bank", description = "Per-topic question bank managed by Admin, browsed by Instructor; Group Leaders can send questions for their own topic")
@SecurityRequirement(name = "bearerAuth")
public class QuestionBankController {

    private final QuestionBankService questionBankService;

    /** Admin: any topic. Group Leader: only their own group's topic (checked in the service). */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','GROUP_LEADER')")
    public ResponseEntity<QuestionResponse> create(@PathVariable UUID topicId,
                                                     @Valid @RequestBody QuestionCreateRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        QuestionBankItem created = questionBankService.create(topicId, request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/topics/" + topicId + "/questions/" + created.getId()))
                .body(QuestionResponse.from(created));
    }

    @GetMapping
    public Page<QuestionResponse> list(@PathVariable UUID topicId,
                                        @RequestParam(required = false) String category,
                                        Pageable pageable) {
        return questionBankService.listByTopic(topicId, category, pageable).map(QuestionResponse::from);
    }
}
