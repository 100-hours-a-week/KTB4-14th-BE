package com.audigo.domain.user.controller;

import com.audigo.domain.user.dto.MyPageResponse;
import com.audigo.domain.user.dto.UpdateNicknameRequest;
import com.audigo.domain.user.dto.UpdateNicknameResponse;
import com.audigo.domain.user.service.UserService;
import com.audigo.global.response.ApiResponse;
import com.audigo.global.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final CurrentUser currentUser;
    private final UserService userService;

    public UserController(CurrentUser currentUser, UserService userService) {
        this.currentUser = currentUser;
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<MyPageResponse> getMe() {
        return ApiResponse.of("my_page_found", userService.getMyPage(currentUser.id()));
    }

    @PatchMapping("/me/nickname")
    public ApiResponse<UpdateNicknameResponse> updateNickname(
            @Valid @RequestBody UpdateNicknameRequest request
    ) {
        return ApiResponse.of(
                "nickname_updated",
                userService.updateNickname(currentUser.id(), request.nickname())
        );
    }
}
