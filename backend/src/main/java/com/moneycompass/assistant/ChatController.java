package com.moneycompass.assistant;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Ask Compass: questions about the caller's own results for one session. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AssistantService assistantService;

    public ChatController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    /** The opening message and suggested questions, tailored to the screen the chat was opened from. */
    @GetMapping("/{sessionId}/intro")
    public ChatIntro intro(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                           @RequestParam(defaultValue = "score") String screen) {
        return assistantService.intro(sessionId, UUID.fromString(jwt.getSubject()), screen);
    }

    @PostMapping("/{sessionId}")
    public ChatResponse chat(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                             @Valid @RequestBody ChatRequest request) {
        return assistantService.chat(sessionId, UUID.fromString(jwt.getSubject()), request);
    }
}
