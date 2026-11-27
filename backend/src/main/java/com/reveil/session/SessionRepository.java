package com.reveil.session;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface SessionRepository extends MongoRepository<Session, String> {

    /** Toutes les sessions (tous appareils) de l'utilisateur : suppression du compte. */
    long deleteByUserId(String userId);
}
