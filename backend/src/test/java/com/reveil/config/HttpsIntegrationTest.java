package com.reveil.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * TLS terminé par l'API (REVEIL_TLS_ENABLED / REVEIL_TLS_CERTIFICATE / REVEIL_TLS_PRIVATE_KEY, PEM) avec un
 * certificat autosigné généré pour le test : HTTPS servi, HSTS envoyé, HTTP en clair impossible sur le port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HttpsIntegrationTest {

    static final char[] STORE_PASSWORD = "test-only".toCharArray();
    static Path dir;
    static KeyStore keyStore;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void tls(DynamicPropertyRegistry registry) throws Exception {
        dir = Files.createTempDirectory("reveil-tls");
        dir.toFile().deleteOnExit();
        Path p12 = dir.resolve("test.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "reveil", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", p12.toString(), "-storepass", new String(STORE_PASSWORD))
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor()).as(output).isZero();

        keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(p12.toFile())) {
            keyStore.load(in, STORE_PASSWORD);
        }
        Certificate cert = keyStore.getCertificate("reveil");
        PrivateKey key = (PrivateKey) keyStore.getKey("reveil", STORE_PASSWORD);
        Path certPem = write(dir.resolve("cert.pem"), "CERTIFICATE", cert.getEncoded());
        Path keyPem = write(dir.resolve("key.pem"), "PRIVATE KEY", key.getEncoded());

        registry.add("server.ssl.enabled", () -> "true");
        registry.add("server.ssl.certificate", certPem::toString);
        registry.add("server.ssl.certificate-private-key", keyPem::toString);
        registry.add("reveil.security.require-https", () -> "true");
    }

    private static Path write(Path file, String type, byte[] der) throws IOException {
        String pem = "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
        file.toFile().deleteOnExit();
        return Files.writeString(file, pem);
    }

    private HttpClient httpsClient() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).build();
    }

    @Test
    void apiServieEnHttpsAvecHsts() throws Exception {
        HttpResponse<String> res = httpsClient().send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"x@y.co\",\"password\":\"secret123\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Requête traitée par l'API (identifiants inconnus), pas refusée par l'exigence HTTPS.
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("E-mail ou mot de passe incorrect");
        assertThat(res.headers().firstValue("Strict-Transport-Security")).hasValueSatisfying(
                v -> assertThat(v).contains("max-age=31536000"));
        assertThat(res.sslSession()).hasValueSatisfying(
                s -> assertThat(s.getProtocol()).isIn("TLSv1.3", "TLSv1.2"));
    }

    @Test
    void httpEnClairImpossible() throws Exception {
        HttpClient plain = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")).build();
        // Tomcat répond 400 à une requête en clair sur le port TLS (ou ferme la connexion).
        try {
            HttpResponse<String> res = plain.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(400);
        } catch (IOException e) {
            // Connexion fermée par le serveur : aucune réponse en clair, comportement attendu.
        }
    }
}
