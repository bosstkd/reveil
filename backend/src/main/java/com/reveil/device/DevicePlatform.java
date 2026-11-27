package com.reveil.device;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Plate-forme de l'appareil (« android » / « ios » en JSON). */
public enum DevicePlatform {
    ANDROID,
    IOS;

    @JsonCreator
    public static DevicePlatform fromJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Plate-forme inconnue : " + value);
        }
    }

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
