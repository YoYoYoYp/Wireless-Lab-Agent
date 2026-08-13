package com.njupt.wirelesslabagent.controller;

import com.njupt.wirelesslabagent.common.BaseResponse;
import com.njupt.wirelesslabagent.common.ResuitUtils;
import com.njupt.wirelesslabagent.service.UserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    public record AuthRequest(String username, String password) {}
    public record TokenData(String token, String username) {}

    @PostMapping("/auth/register")
    public BaseResponse<?> register(@RequestBody AuthRequest req) {
        if (!userService.register(req.username, req.password)) {
            return ResuitUtils.error(50001, "用户名已存在");
        }
        return ResuitUtils.success("注册成功");
    }

    @PostMapping("/auth/login")
    public BaseResponse<?> login(@RequestBody AuthRequest req) {
        String token = userService.login(req.username, req.password);
        if (token == null) {
            return ResuitUtils.error(40100, "用户名或密码错误");
        }
        return ResuitUtils.success(new TokenData(token, req.username));
    }
}
