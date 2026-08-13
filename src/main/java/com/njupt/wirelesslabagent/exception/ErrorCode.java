package com.njupt.wirelesslabagent.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
//异常对象
    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000,"请求参数错误"),
    NOT_LOGIN_ERROR(40100,"未登录"),
    NO_AUTH_ERROR(40101,"无权限"),
    NOT_FOUND_ERROR(40400,"请求数据不存在"),
    FORBIDDEN_ERROR(40300,"禁止访问"),
    SYSTEM_ERROR(50000,"系统内部异常"),
    OPERATION_ERROR(50001,"操作失败"),
    AI_SERVICE_ERROR(50002,"AI服务异常"),
    TOOL_CALL_ERROR(50003,"工具调用失败"),
    AI_TIMEOUT_ERROR(50400,"AI请求超时");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;

    }
    }