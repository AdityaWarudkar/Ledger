package dev.ledger.api;

import dev.ledger.account.AccountNotFoundException;
import dev.ledger.transfer.TransferNotFoundException;
import dev.ledger.transfer.TransferRejectedException;
import java.util.LinkedHashMap;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(TransferRejectedException.class)
    public ProblemDetail transferRejected(TransferRejectedException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
        problem.setTitle("Transfer rejected");
        problem.setProperty("code", exception.getCode());
        return problem;
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail transferNotFound(TransferNotFoundException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Transfer not found");
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail accountNotFound(AccountNotFoundException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Account not found");
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = new LinkedHashMap<String, String>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Check the submitted fields");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }
}
