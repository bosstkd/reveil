package com.reveil.webpush;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WebPushSubscriptionRepository extends MongoRepository<WebPushSubscription, String> {

    Optional<WebPushSubscription> findByEndpoint(String endpoint);

    List<WebPushSubscription> findByUserId(String userId);

    long deleteByEndpoint(String endpoint);

    long deleteByEndpointAndUserId(String endpoint, String userId);

    long deleteByUserId(String userId);
}
