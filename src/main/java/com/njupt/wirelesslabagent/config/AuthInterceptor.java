package com.njupt.wirelesslabagent.config;

import com.njupt.wirelesslabagent.exception.BusinessException;
import com.njupt.wirelesslabagent.exception.ErrorCode;
import com.njupt.wirelesslabagent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        // 登录和注册不需要鉴权（context-path 下不带 /api 前缀）
        if (path.startsWith("/auth/")) {
            return true;
        }
        // 静态资源放行（.html .css .js 等）
        if (path.contains(".")) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }

        String token = authHeader.substring(7);
        String username = userService.validateToken(token);
        if (username == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "Token 已过期或无效");
        }

        request.setAttribute("currentUser", username);
        return true;
    }
}
