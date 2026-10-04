package com.capstone.tracking.topic;

import io.swagger.v3.oas.annotations.Operation;
import com.capstone.tracking.topic.dto.TopicCreateRequest;
import com.capstone.tracking.topic.dto.TopicResponse;
import com.capstone.tracking.topic.dto.TopicUpdateRequest;
import com.capstone.tracking.user.User;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** FR-005: Admin manages the topic catalogue; every authenticated role may browse it. */
@RestController
@RequestMapping("/api/v1/topics")
@RequiredArgsConstructor
@Tag(name = "Topics", description = "Topic catalogue managed by Admin")
@SecurityRequirement(name = "bearerAuth")
public class TopicController {

    private final TopicService topicService;

    @Operation(summary = "Create a topic")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TopicResponse> create(@Valid @RequestBody TopicCreateRequest request,
                                                 @AuthenticationPrincipal User currentUser) {
        Topic created = topicService.create(request, currentUser);
        return ResponseEntity.created(URI.create("/api/v1/topics/" + created.getId()))
                .body(TopicResponse.from(created));
    }

    @Operation(summary = "List topics")
    @GetMapping
    public Page<TopicResponse> list(@RequestParam(required = false) TopicStatus status, @ParameterObject Pageable pageable) {
        return topicService.list(status, pageable).map(TopicResponse::from);
    }

    @Operation(summary = "Get a topic")
    @GetMapping("/{id}")
    public TopicResponse getById(@PathVariable UUID id) {
        return TopicResponse.from(topicService.getById(id));
    }

    @Operation(summary = "Update a topic")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TopicResponse update(@PathVariable UUID id, @Valid @RequestBody TopicUpdateRequest request) {
        return TopicResponse.from(topicService.update(id, request));
    }
}
