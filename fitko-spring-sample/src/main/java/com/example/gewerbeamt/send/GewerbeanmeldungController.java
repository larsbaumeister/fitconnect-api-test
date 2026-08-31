package com.example.gewerbeamt.send;

import com.gfi.ozg.fitko.spring.send.AntragSendException;
import dev.fitko.fitconnect.api.domain.model.submission.SentSubmission;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Turns an HTTP request into a FIT-Connect send. Only here to give the
 * sample something to invoke - the reusable part is {@link GewerbeanmeldungService}.
 *
 * <pre>
 * curl -sS -X POST localhost:8080/api/gewerbeanmeldungen \
 *   -H 'Content-Type: application/json' \
 *   -d '{
 *         "destinationId": "9f6bb611-df46-494a-9a98-a253f1362dc7",
 *         "businessName":  "Baeckerei Mustermann",
 *         "ownerName":     "Erika Mustermann",
 *         "applicantEmail":"erika@example.com"
 *       }'
 * </pre>
 */
@RestController
@RequestMapping("/api/gewerbeanmeldungen")
public class GewerbeanmeldungController {

    private final GewerbeanmeldungService service;

    public GewerbeanmeldungController(GewerbeanmeldungService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SendResponse send(@Valid @RequestBody GewerbeanmeldungRequest request) {
        SentSubmission sent = service.submit(request);
        return new SendResponse(sent.getSubmissionId(), sent.getCaseId());
    }

    /** What FIT-Connect assigned to the accepted submission. */
    public record SendResponse(UUID submissionId, UUID caseId) {
    }

    /** Surface a failed send as 502 rather than a bare 500. */
    @ExceptionHandler(AntragSendException.class)
    public ProblemDetail handleSendFailure(AntragSendException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setTitle("FIT-Connect send failed");
        return problem;
    }
}
