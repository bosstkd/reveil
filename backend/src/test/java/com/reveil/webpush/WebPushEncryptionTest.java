package com.reveil.webpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import org.junit.jupiter.api.Test;

class WebPushEncryptionTest {

    /** RFC 8291, section 5 : « When I grow up, I want to be a watermelon ». */
    @Test
    void reproduitLeVecteurDeLaRfc8291() {
        String uaPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
        String auth = "BTBZMqHH6r4Tts7J_aSIgg";
        ECPrivateKey asPrivate = EcKeys.privateKey(EcKeys.base64Url("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
        KeyPair sender = new KeyPair(EcKeys.publicKey(EcKeys.base64Url(
                "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")), asPrivate);

        byte[] body = WebPushEncryption.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII),
                EcKeys.base64Url(uaPublic), EcKeys.base64Url(auth), sender, EcKeys.base64Url("DGv6ra1nlYgDCS1FRnbzlw"));

        assertThat(EcKeys.base64Url(body)).isEqualTo(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK"
                        + "6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void seulLeNavigateurAbonnePeutDechiffrer() throws Exception {
        TestBrowser browser = new TestBrowser();
        String message = "{\"title\":\"Rappel : Fête d'été\"}";
        byte[] body = WebPushEncryption.encrypt(message.getBytes(StandardCharsets.UTF_8),
                EcKeys.base64Url(browser.p256dh()), browser.auth);
        assertThat(browser.decrypt(body)).isEqualTo(message);

        // Deux envois du même contenu : clé éphémère et sel différents.
        byte[] again = WebPushEncryption.encrypt(message.getBytes(StandardCharsets.UTF_8),
                EcKeys.base64Url(browser.p256dh()), browser.auth);
        assertThat(again).isNotEqualTo(body);

        TestBrowser other = new TestBrowser();
        assertThatThrownBy(() -> other.decrypt(body)).isInstanceOf(Exception.class);
    }

    @Test
    void refuseUnContenuTropLongOuUneCleHorsCourbe() {
        TestBrowser browser = new TestBrowser();
        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[WebPushEncryption.MAX_PLAINTEXT + 1],
                EcKeys.base64Url(browser.p256dh()), browser.auth)).isInstanceOf(IllegalArgumentException.class);
        byte[] notOnCurve = EcKeys.base64Url(browser.p256dh());
        notOnCurve[64] ^= 1;
        assertThatThrownBy(() -> EcKeys.publicKey(notOnCurve)).isInstanceOf(IllegalArgumentException.class);
    }
}
