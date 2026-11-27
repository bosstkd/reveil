package com.reveil.device;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Jeton push d'un appareil (FCM ; sur iOS, FCM relaie vers APNs). Un jeton = un appareil : il est unique
 * et rattaché au dernier utilisateur / à la dernière session qui l'a enregistré.
 */
@Document(collection = "device_tokens")
public class DeviceToken {

    @Id
    private String id;

    @Indexed(unique = true, name = "uk_device_tokens_token")
    private String token;

    @Indexed(name = "ix_device_tokens_user")
    private String userId;

    /** Session de l'appareil : une fois révoquée ou expirée (déconnexion), le jeton n'est plus utilisé. */
    private String sessionId;

    private DevicePlatform platform;

    private Instant createdAt;

    private Instant updatedAt;

    protected DeviceToken() {
    }

    public DeviceToken(String token, String userId, String sessionId, DevicePlatform platform, Instant now) {
        this.token = token;
        this.userId = userId;
        this.sessionId = sessionId;
        this.platform = platform;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void reassign(String userId, String sessionId, DevicePlatform platform, Instant now) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.platform = platform;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public DevicePlatform getPlatform() {
        return platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
