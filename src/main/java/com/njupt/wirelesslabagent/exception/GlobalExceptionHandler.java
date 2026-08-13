package com.njupt.wirelesslabagent.exception;

import com.njupt.wirelesslabagent.common.BaseResponse;
import com.njupt.wirelesslabagent.common.ResuitUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> validationExceptionHandler(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return ResuitUtils.error(ErrorCode.PARAMS_ERROR.getCode(), msg);
    }

    @ExceptionHandler(NonTransientAiException.class)
    public BaseResponse<?> aiExceptionHandler(NonTransientAiException e) {
        log.error("AI 服务异常: {}", e.getMessage());
        return ResuitUtils.error(ErrorCode.AI_SERVICE_ERROR.getCode(),
                ErrorCode.AI_SERVICE_ERROR.getMessage());
    }

    @ExceptionHandler(RestClientResponseException.class)
    public BaseResponse<?> httpClientExceptionHandler(RestClientResponseException e) {
        log.error("外部服务调用失败: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
        return ResuitUtils.error(ErrorCode.AI_SERVICE_ERROR.getCode(),
                "外部服务调用失败: " + e.getStatusCode());
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException: {}", e.getMessage());
        return ResuitUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResuitUtils.error(ErrorCode.SYSTEM_ERROR.getCode(), "系统错误");
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse<?> exceptionHandler(Exception e) {
        log.error("未捕获异常", e);
        return ResuitUtils.error(ErrorCode.SYSTEM_ERROR.getCode(), "系统内部异常");
    }

}
