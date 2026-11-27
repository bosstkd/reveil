package com.reveil.push;

/** Issue d'un envoi. */
public enum PushResult {
    /** Accepté par le service push. */
    SENT,
    /** Jeton définitivement invalide (application désinstallée, jeton expiré ou d'un autre projet) : à supprimer. */
    INVALID_TOKEN,
    /** Échec (réseau, quota, panne) : jeton conservé. */
    FAILED
}
