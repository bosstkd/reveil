package com.reveil.device;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reveil.auth.TokenService;
import com.reveil.config.ApiExceptionHandler;
import com.reveil.config.SecurityConfig;
import com.reveil.session.SessionService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DeviceController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class})
class DeviceControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DeviceService deviceService;
    @MockitoBean
    TokenService tokenService;
    @MockitoBean
    SessionService sessionService;

    @BeforeEach
    void authenticate() {
        when(tokenService.verify("good")).thenReturn(Optional.of(new TokenService.AccessToken("u1", "a@b.co", "s1")));
        when(sessionService.isActive("s1")).thenReturn(true);
    }

    @Test
    void registerRequiresAuthentication() throws Exception {
        mvc.perform(put("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-1\",\"platform\":\"android\"}"))
                .andExpect(status().isUnauthorized());
        verify(deviceService, never()).register(any(), any(), any());
    }

    @Test
    void registerStoresTheTokenForTheCurrentUserAndSession() throws Exception {
        mvc.perform(put("/api/devices").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-1\",\"platform\":\"ios\"}"))
                .andExpect(status().isNoContent());
        verify(deviceService).register(eq("u1"), eq("s1"), eq(new RegisterDeviceRequest("fcm-1", DevicePlatform.IOS)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"platform\":\"android\"}", "{\"token\":\"  \",\"platform\":\"android\"}",
            "{\"token\":\"fcm-1\"}"})
    void missingFieldsAreRejected(String body) throws Exception {
        mvc.perform(put("/api/devices").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(1));
        verify(deviceService, never()).register(any(), any(), any());
    }

    @Test
    void unknownPlatformIsRejected() throws Exception {
        mvc.perform(put("/api/devices").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-1\",\"platform\":\"windows\"}"))
                .andExpect(status().isBadRequest());
    }
}
