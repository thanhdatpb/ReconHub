package vn.reconhub.api.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.api.security.CurrentUser;
import vn.reconhub.core.ai.AssistantService;

/** The data assistant. The LLM only sees what the read-only tools return for this user's role. */
@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AssistantService assistant;
    private final CurrentUser current;

    public AiController(AssistantService assistant, CurrentUser current) {
        this.assistant = assistant;
        this.current = current;
    }

    public record Question(@NotBlank @Size(max = 500) String question) {}

    @PostMapping("/ask")
    public AssistantService.Answer ask(@Valid @RequestBody Question q) {
        return assistant.ask(q.question(), current.get());
    }
}
