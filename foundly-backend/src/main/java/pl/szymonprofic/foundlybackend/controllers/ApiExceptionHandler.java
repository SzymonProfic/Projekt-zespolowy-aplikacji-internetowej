package pl.szymonprofic.foundlybackend.controllers;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail optimisticConflict(OptimisticLockingFailureException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Zgłoszenie zostało zmienione; pobierz aktualną wersję");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidParameters(ConstraintViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Niepoprawne parametry żądania");
    }
}
