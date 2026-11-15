package com.example.gateway.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions thrown by controllers (proxy and admin) into the same problem+json responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public void gateway(GatewayException e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        e.writeTo(request, response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void invalid(MethodArgumentNotValidException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        new GatewayException(ErrorCode.VALIDATION_ERROR, detail).writeTo(request, response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void unreadable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        new GatewayException(ErrorCode.VALIDATION_ERROR, "malformed JSON body").writeTo(request, response);
    }
}
