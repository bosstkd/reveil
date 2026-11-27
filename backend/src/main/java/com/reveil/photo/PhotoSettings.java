package com.reveil.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Réglages des photos ({@code reveil.photos.*}).
 *
 * @param maxSize          taille maximale d'un fichier envoyé (défaut 15 Mo ; le mobile envoie ~0,5–2 Mo après compression)
 * @param maxPixels        nombre maximal de pixels de l'image d'origine (protection contre les « bombes » de décompression)
 * @param thumbnailSize    plus grand côté de la miniature, en pixels
 * @param maxBatchFiles    nombre maximal de fichiers par envoi multiple ({@code POST .../photos/batch})
 */
@ConfigurationProperties(prefix = "reveil.photos")
public record PhotoSettings(@DefaultValue("15MB") DataSize maxSize,
                            @DefaultValue("50000000") long maxPixels,
                            @DefaultValue("400") int thumbnailSize,
                            @DefaultValue("10") int maxBatchFiles) {
}
