package com.microchip.delta_core.web;

import com.microchip.delta_core.domain.FeedbackMessage;
import com.microchip.delta_core.service.FeedbackService;
import com.microchip.delta_core.service.Refusal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// The sheet is open to anyone; reading is for admins (role ADMIN in the token of pathos-auth).
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    public record SubmitRequest(String message, String contact) {
    }

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(@RequestBody SubmitRequest request, HttpServletRequest http) {
        feedback.submit(sender(http), request.message(), request.contact());
    }

    @GetMapping
    public List<FeedbackMessage> list() {
        return feedback.list();
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable long id) {
        feedback.markRead(id);
    }

    // the service is only reachable through the gateway, which sets X-Real-IP
    private static String sender(HttpServletRequest http) {
        String real = http.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real : http.getRemoteAddr();
    }

    @ExceptionHandler(Refusal.class)
    ResponseEntity<ProblemDetail> handleRefusal(Refusal e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        problem.setProperties(Map.of("code", e.code()));
        return ResponseEntity.status(e.status()).body(problem);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ProblemDetail handleBadRequest(Exception e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e instanceof IllegalArgumentException ? e.getMessage() : "некорректный запрос");
        problem.setProperties(Map.of("code", "bad_request"));
        return problem;
    }
}
