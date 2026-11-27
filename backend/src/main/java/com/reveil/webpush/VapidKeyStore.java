package com.reveil.webpush;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Clock;
import java.util.Date;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Clés VAPID : celles de la configuration ({@code REVEIL_WEBPUSH_PUBLIC_KEY} / {@code REVEIL_WEBPUSH_PRIVATE_KEY}) ou,
 * à défaut, une paire générée une fois et conservée en base : stable d'un redémarrage à l'autre et partagée par
 * toutes les instances (création atomique), sans quoi les abonnements existants deviendraient inutilisables.
 */
@Component
public class VapidKeyStore {

    static final String COLLECTION = "web_push_vapid_keys";
    private static final Logger log = LoggerFactory.getLogger(VapidKeyStore.class);

    private final MongoTemplate mongo;
    private final WebPushSettings settings;
    private final Clock clock = Clock.systemUTC();
    private volatile VapidKeys keys;

    public VapidKeyStore(MongoTemplate mongo, WebPushSettings settings) {
        this.mongo = mongo;
        this.settings = settings;
    }

    public VapidKeys keys() {
        VapidKeys current = keys;
        if (current == null) {
            synchronized (this) {
                if (keys == null) {
                    keys = resolve();
                }
                current = keys;
            }
        }
        return current;
    }

    private VapidKeys resolve() {
        boolean hasPublic = !settings.publicKey().isBlank();
        boolean hasPrivate = !settings.privateKey().isBlank();
        if (hasPublic && hasPrivate) {
            return VapidKeys.fromBase64Url(settings.publicKey(), settings.privateKey());
        }
        if (hasPublic || hasPrivate) {
            throw new IllegalStateException("reveil.push.web.public-key et private-key doivent être fournies ensemble");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                VapidKeys generated = VapidKeys.generate();
                Document doc = mongo.findAndModify(Query.query(where("_id").is("vapid")),
                        new Update().setOnInsert("publicKey", generated.publicKey())
                                .setOnInsert("privateKey", generated.privateKeyBase64Url())
                                .setOnInsert("createdAt", Date.from(clock.instant())),
                        FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, COLLECTION);
                if (doc.getString("publicKey").equals(generated.publicKey())) {
                    log.warn("Clés VAPID absentes de la configuration : paire générée et conservée en base ({})",
                            COLLECTION);
                }
                return VapidKeys.fromBase64Url(doc.getString("publicKey"), doc.getString("privateKey"));
            } catch (DuplicateKeyException e) {
                // Création simultanée par une autre instance : relecture du document créé.
                if (attempt >= 1) {
                    throw e;
                }
            }
        }
    }
}
