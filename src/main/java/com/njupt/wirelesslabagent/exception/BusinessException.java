package com.njupt.wirelesslabagent.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
//    错误码
    private final int code ;
    //抛出一个未曾在ErrorCode中枚举过的异常
    public BusinessException(int code,String message){
        super(message);
        this.code =code;
    }
    //调用ErroCode中枚举的异常
    public BusinessException(ErrorCode errorcode){
        super(errorcode.getMessage());
        this.code = errorcode.getCode();
    }
    //code不变新增message的异常
    public BusinessException(ErrorCode errorCode,String message){
        super(message);
        this.code = errorCode.getCode();
    }
}
