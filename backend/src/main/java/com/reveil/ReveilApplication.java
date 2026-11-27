package com.reveil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// Pas d'utilisateur en mémoire généré : l'authentification passe par les jetons JWT.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ReveilApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReveilApplication.class, args);
    }
}
