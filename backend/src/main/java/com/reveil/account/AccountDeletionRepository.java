package com.reveil.account;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AccountDeletionRepository extends MongoRepository<AccountDeletion, String> {

    /** Demandes à (re)purger : antérieures à {@code before}, les plus anciennes d'abord. */
    List<AccountDeletion> findByRequestedAtLessThanEqualOrderByRequestedAtAsc(Instant before);
}
