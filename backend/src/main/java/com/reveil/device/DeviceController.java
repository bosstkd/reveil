package com.reveil.device;

import com.reveil.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Appareils de l'utilisateur connecté (notifications push). Route protégée. */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** {@code PUT /api/devices} : enregistre le jeton push de l'appareil courant (idempotent). */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@Valid @RequestBody RegisterDeviceRequest request,
                         @AuthenticationPrincipal AuthenticatedUser current) {
        deviceService.register(current.id(), current.sessionId(), request);
    }
}
