package com.reveil.device;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeviceTokenRepository extends MongoRepository<DeviceToken, String> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByUserId(String userId);

    void deleteByToken(String token);

    long deleteByUserId(String userId);
}
