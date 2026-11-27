package com.reveil.auth;

public record RefreshRequest(String refreshToken) {

    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=***]";
    }
}
