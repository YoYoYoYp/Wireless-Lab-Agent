package com.njupt.wirelesslabagent.util;

import com.njupt.wirelesslabagent.exception.BusinessException;
import com.njupt.wirelesslabagent.exception.ErrorCode;

public class ThrowUtils {
    public static void throwIf(boolean condition, RuntimeException runtimeException){

        if (condition){
            throw runtimeException;
        }
    }

    public static void throwIf(boolean condition, ErrorCode errorCode){
        throwIf(condition,new BusinessException(errorCode));
    }
    public static void throwIf(boolean condition,ErrorCode errorCode,String message){
        throwIf(condition,new BusinessException(errorCode,message));
    }

}
