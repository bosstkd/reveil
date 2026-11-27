package com.reveil.photo;

import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;

/** Fichiers des photos dans GridFS (bucket par défaut {@code fs} : collections {@code fs.files} / {@code fs.chunks}). */
@Component
public class PhotoStorage {

    private final GridFsTemplate gridFs;

    public PhotoStorage(GridFsTemplate gridFs) {
        this.gridFs = gridFs;
    }

    /** Enregistre {@code data} ; renvoie l'identifiant GridFS. */
    public String store(byte[] data, String filename, String contentType, String ownerId, String eventId) {
        Document metadata = new Document("ownerId", ownerId).append("eventId", eventId);
        ObjectId id = gridFs.store(new ByteArrayInputStream(data), filename, contentType, metadata);
        return id.toHexString();
    }

    /** Contenu d'un fichier, s'il existe encore. */
    public Optional<StoredFile> open(String fileId) {
        if (!ObjectId.isValid(fileId)) {
            return Optional.empty();
        }
        GridFSFile file = gridFs.findOne(Query.query(Criteria.where("_id").is(new ObjectId(fileId))));
        if (file == null) {
            return Optional.empty();
        }
        GridFsResource resource = gridFs.getResource(file);
        try {
            return Optional.of(new StoredFile(resource.getInputStream(), file.getLength()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String fileId) {
        if (fileId != null && ObjectId.isValid(fileId)) {
            gridFs.delete(Query.query(Criteria.where("_id").is(new ObjectId(fileId))));
        }
    }

    /** Supprime tous les fichiers (originaux et miniatures) rattachés à l'événement. */
    public void deleteAllOf(String eventId) {
        gridFs.delete(Query.query(Criteria.where("metadata.eventId").is(eventId)));
    }

    /** Supprime tous les fichiers (originaux et miniatures) de l'utilisateur : suppression du compte. */
    public void deleteAllOwnedBy(String ownerId) {
        gridFs.delete(Query.query(Criteria.where("metadata.ownerId").is(ownerId)));
    }

    public record StoredFile(InputStream content, long length) {
    }
}
