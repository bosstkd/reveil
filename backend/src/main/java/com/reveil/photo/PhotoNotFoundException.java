package com.reveil.photo;

/** Photo inexistante ou appartenant à un autre utilisateur : 404. */
public class PhotoNotFoundException extends RuntimeException {

    public PhotoNotFoundException() {
        super("Photo introuvable");
    }
}
