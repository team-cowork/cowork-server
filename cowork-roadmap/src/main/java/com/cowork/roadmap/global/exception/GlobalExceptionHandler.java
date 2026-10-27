package com.cowork.roadmap.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import team.themoment.sdk.exception.ExpectedException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorMessage(String message) {
    }

    @ExceptionHandler(ExpectedException.class)
    public ResponseEntity<ErrorMessage> handleExpected(ExpectedException e) {
        HttpStatus status = e.getStatusCode();
        String message = e.getMessage() != null ? e.getMessage() : status.getReasonPhrase();
        return ResponseEntity.status(status).body(new ErrorMessage(message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorMessage> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("요청 값이 올바르지 않습니다.");
        return ResponseEntity.badRequest().body(new ErrorMessage(message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ErrorMessage> handleInput(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorMessage("요청 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorMessage> handleInternal(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            if (errorResponse.getStatusCode().is5xxServerError()) {
                log.error("Failed to process an HTTP request", e);
            }
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .headers(errorResponse.getHeaders())
                    .body(new ErrorMessage("요청을 처리할 수 없습니다."));
        }
        log.error("Unhandled exception occurred", e);
        return ResponseEntity.internalServerError().body(new ErrorMessage("서버 오류가 발생했습니다."));
    }
}
