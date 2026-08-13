/* 全局异常边界：将校验、业务、上传和未知异常归一化为不泄密的 ApiError。 */
package com.company.personnelarchive.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException exception) {
        log.warn("event=request_business_rejected code={}", exception.code().name());
        return ResponseEntity.status(exception.code().status())
            .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> handleValidation(Exception exception) {
        log.warn("event=request_validation_rejected category=bean_validation");
        Map<String, String> details = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentNotValidException invalid) {
            invalid.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        }
        return ResponseEntity.badRequest().body(new ApiError(ErrorCode.VALIDATION_ERROR.name(),
            ErrorCode.VALIDATION_ERROR.message(), java.time.Instant.now().toString(), details.isEmpty() ? null : details));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<ApiError> handleMalformedRequest(Exception exception) {
        log.warn("event=request_validation_rejected category=malformed_request");
        return ResponseEntity.badRequest().body(ApiError.of(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.message()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleUploadTooLarge() {
        log.warn("event=request_validation_rejected category=upload_too_large");
        return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.status())
            .body(ApiError.of(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.message()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleForbidden() {
        log.warn("event=request_validation_rejected category=forbidden");
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
            .body(ApiError.of(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.message()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        String requestId = RequestCorrelation.currentIdOrNew();
        log.error("event=request_failed requestId={} {}", requestId, SafeExceptionSummary.describe(exception));
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
            .body(ApiError.withRequestId(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(), requestId));
    }
}
