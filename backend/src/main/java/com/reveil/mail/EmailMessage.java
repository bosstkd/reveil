package com.reveil.mail;

/** E-mail texte à envoyer. {@code toString} ne contient pas le corps (lien de réinitialisation : secret). */
public record EmailMessage(String to, String subject, String body) {

    @Override
    public String toString() {
        return "EmailMessage[to=" + to + ", subject=" + subject + ", body=***]";
    }
}
