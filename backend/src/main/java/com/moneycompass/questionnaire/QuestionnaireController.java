package com.moneycompass.questionnaire;

import com.moneycompass.questionnaire.dto.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/questionnaire")
public class QuestionnaireController {

    private final QuestionnaireService questionnaireService;

    public QuestionnaireController(QuestionnaireService questionnaireService) {
        this.questionnaireService = questionnaireService;
    }

    @PostMapping("/start")
    public StartSessionResponse start(@AuthenticationPrincipal Jwt jwt) {
        return questionnaireService.start(userId(jwt));
    }

    @GetMapping("/{sessionId}/next")
    public NextQuestionResponse next(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return questionnaireService.next(sessionId, userId(jwt));
    }

    @PostMapping("/{sessionId}/answer")
    public AnswerResponse answer(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sessionId,
            @Valid @RequestBody AnswerRequest request) {
        return questionnaireService.answer(sessionId, userId(jwt), request);
    }

    /** Steps back one question, returning it with the answer that was removed so it can be pre-filled. */
    @PostMapping("/{sessionId}/back")
    public BackResponse back(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return questionnaireService.back(sessionId, userId(jwt));
    }

    @PostMapping("/{sessionId}/complete")
    public CompleteResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return questionnaireService.complete(sessionId, userId(jwt));
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
