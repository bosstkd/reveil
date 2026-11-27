package com.reveil.device;

import com.reveil.session.SessionService;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Jetons push des appareils, par utilisateur. */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceTokenRepository devices;
    private final SessionService sessions;
    private final Clock clock;

    @Autowired
    public DeviceService(DeviceTokenRepository devices, SessionService sessions) {
        this(devices, sessions, Clock.systemUTC());
    }

    DeviceService(DeviceTokenRepository devices, SessionService sessions, Clock clock) {
        this.devices = devices;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Enregistre (ou rattache) le jeton à l'utilisateur et à la session courants. Idempotent.
     * Un jeton déjà connu (autre compte connecté auparavant sur l'appareil) change de propriétaire.
     */
    public void register(String userId, String sessionId, RegisterDeviceRequest request) {
        String token = request.token().trim();
        for (int attempt = 0; ; attempt++) {
            try {
                DeviceToken device = devices.findByToken(token)
                        .map(d -> {
                            d.reassign(userId, sessionId, request.platform(), clock.instant());
                            return d;
                        })
                        .orElseGet(() -> new DeviceToken(token, userId, sessionId, request.platform(), clock.instant()));
                devices.save(device);
                return;
            } catch (DuplicateKeyException e) {
                // Deux enregistrements simultanés du même jeton : on relit le document créé par l'autre requête.
                if (attempt >= 1) {
                    throw e;
                }
            }
        }
    }

    /**
     * Jetons utilisables de l'utilisateur : ceux dont la session est encore active. Les autres (appareil
     * déconnecté, session expirée ou révoquée) sont supprimés au passage.
     */
    public List<DeviceToken> activeDevicesOf(String userId) {
        return devices.findByUserId(userId).stream()
                .filter(d -> {
                    if (sessions.isActive(d.getSessionId())) {
                        return true;
                    }
                    devices.delete(d);
                    return false;
                })
                .toList();
    }

    /** Jeton refusé par le service push (désinstallation, jeton expiré…) : il est oublié. */
    public void forget(String token) {
        devices.deleteByToken(token);
        log.info("Jeton d'appareil invalide supprimé");
    }
}
