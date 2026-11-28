package com.example.hadaq.Advice;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.Api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@org.springframework.web.bind.annotation.ControllerAdvice
public class ControllerAdvice {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse> apiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ApiResponse(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> methodArgumentNotValidException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .map(error -> error.getDefaultMessage() == null ? "Invalid request field" : error.getDefaultMessage())
                .findFirst().orElse("Invalid request body.");
        return ResponseEntity.status(400).body(new ApiResponse(message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse> constraintViolationException(ConstraintViolationException exception) {
        return ResponseEntity.status(400).body(new ApiResponse(exception.getMessage()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse> handlerMethodValidationException(HandlerMethodValidationException exception) {
        return ResponseEntity.status(400).body(new ApiResponse("Request parameters are outside their allowed range."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse> dataIntegrityViolationException(DataIntegrityViolationException exception) {
        return ResponseEntity.status(400).body(new ApiResponse("The operation conflicts with existing data or references."));
    }

    // bad JSON, or a value that is not in the contract (an unknown event type, kind, provenance ...)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse> httpMessageNotReadableException(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(400).body(new ApiResponse("Request body is missing or contains invalid JSON or field values."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> methodArgumentTypeMismatchException(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.status(400).body(new ApiResponse("Invalid value for parameter '" + exception.getName() + "'."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse> missingServletRequestParameterException(MissingServletRequestParameterException exception) {
        return ResponseEntity.status(400).body(new ApiResponse("Missing parameter '" + exception.getParameterName() + "'."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse> missingServletRequestPartException(MissingServletRequestPartException exception) {
        return ResponseEntity.status(400).body(new ApiResponse("Missing part '" + exception.getRequestPartName() + "'."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse> maxUploadSizeExceededException(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(400).body(new ApiResponse("The photo is too large (20 MB max)."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse> httpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(405).body(new ApiResponse(exception.getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse> httpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(415).body(new ApiResponse("Unsupported Content-Type: " + exception.getContentType()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse> noResourceFoundException(NoResourceFoundException exception) {
        return ResponseEntity.status(404).body(new ApiResponse("Endpoint not found: " + exception.getResourcePath()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse> runtimeException(RuntimeException exception) {
        log.error("Unexpected request failure", exception);
        return ResponseEntity.status(500).body(new ApiResponse("Unable to complete the request."));
    }

    // checked exceptions (IOException ...): RuntimeException above does not catch them
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> exception(Exception exception) {
        log.error("Unexpected request failure", exception);
        return ResponseEntity.status(500).body(new ApiResponse("Unable to complete the request."));
    }
}
