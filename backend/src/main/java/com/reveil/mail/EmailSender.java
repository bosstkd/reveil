package com.reveil.mail;

/** Envoi d'e-mails transactionnels. Lève une exception si l'envoi échoue. */
public interface EmailSender {

    void send(EmailMessage message);
}
