package com.reveil.auth;

public record AuthResponse(String token, UserDto user) {

    public record UserDto(String id, String email) {
    }
}
