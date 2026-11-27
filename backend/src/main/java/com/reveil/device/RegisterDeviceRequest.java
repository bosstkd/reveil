package com.reveil.device;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Enregistrement du jeton push de l'appareil courant. */
public record RegisterDeviceRequest(
        @NotBlank(message = "Le jeton de l'appareil est obligatoire")
        @Size(max = 4096, message = "Le jeton de l'appareil est trop long")
        String token,

        @NotNull(message = "La plate-forme est obligatoire (android ou ios)")
        DevicePlatform platform) {
}
