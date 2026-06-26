package com.cowork.roadmap.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import team.themoment.sdk.exception.ExpectedException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(String message) {
    }

    @ExceptionHandler(ExpectedException.class)
    public ResponseEntity<ErrorResponse> handleExpected(ExpectedException e) {
        HttpStatus status = e.getStatusCode();
        String message = e.getMessage() != null ? e.getMessage() : status.getReasonPhrase();
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("요청 값이 올바르지 않습니다.");
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleInput(ServerWebInputException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("요청 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleInternal(Exception e) {
        log.error("Unhandled exception occurred", e);
        return ResponseEntity.internalServerError().body(new ErrorResponse("서버 오류가 발생했습니다."));
    }
}
