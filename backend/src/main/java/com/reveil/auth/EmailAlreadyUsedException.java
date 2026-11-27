package com.reveil.auth;

public class EmailAlreadyUsedException extends RuntimeException {

    public static final String MESSAGE = "Un compte existe déjà avec cet e-mail";

    public EmailAlreadyUsedException() {
        super(MESSAGE);
    }
}
