package com.reveil.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reveil.event.Event;
import com.reveil.event.EventNotFoundException;
import com.reveil.event.EventRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class PhotoServiceTest {

    private final EventRepository events = mock(EventRepository.class);
    private final PhotoRepository photos = mock(PhotoRepository.class);
    private final PhotoStorage storage = mock(PhotoStorage.class);
    private final PhotoSettings settings = new PhotoSettings(DataSize.ofMegabytes(1), 50_000_000, 400, 10);
    private final PhotoService service = new PhotoService(events, photos, storage, new ImageProcessor(settings), settings);

    @BeforeEach
    void setUp() {
        when(events.findByIdAndOwnerId("e1", "u1"))
                .thenReturn(Optional.of(new Event("Concert", Instant.parse("2099-01-01T00:00:00Z"), "UTC", null, "u1")));
        when(storage.store(any(), eq("photo.jpg"), anyString(), anyString(), anyString())).thenReturn("f1");
        when(storage.store(any(), eq("thumbnail.jpg"), anyString(), anyString(), anyString())).thenReturn("t1");
        when(photos.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MockMultipartFile file(byte[] data) {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", data);
    }

    @Test
    void uploadStoresOriginalAndThumbnailThenMetadata() {
        byte[] jpeg = TestImages.jpeg(1024, 768);
        PhotoResponse response = service.upload("u1", "e1", file(jpeg));

        assertThat(response.eventId()).isEqualTo("e1");
        assertThat(response.width()).isEqualTo(1024);
        assertThat(response.height()).isEqualTo(768);
        assertThat(response.size()).isEqualTo(jpeg.length);
        assertThat(response.contentType()).isEqualTo("image/jpeg");
        verify(storage).store(jpeg, "photo.jpg", "image/jpeg", "u1", "e1");
        verify(storage).store(any(), eq("thumbnail.jpg"), eq("image/jpeg"), eq("u1"), eq("e1"));
        verify(photos).save(any());
    }

    @Test
    void eventOfAnotherUserIsNotFoundAndNothingIsStored() {
        assertThatThrownBy(() -> service.upload("u2", "e1", file(TestImages.jpeg(10, 10))))
                .isInstanceOf(EventNotFoundException.class);
        verify(storage, never()).store(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyFileIsRejected() {
        assertThatThrownBy(() -> service.upload("u1", "e1", file(new byte[0])))
                .isInstanceOfSatisfying(InvalidPhotoException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.upload("u1", "e1", null)).isInstanceOf(InvalidPhotoException.class);
    }

    @Test
    void fileOverTheConfiguredSizeIsRejectedWith413() {
        byte[] big = new byte[(int) DataSize.ofMegabytes(1).toBytes() + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> service.upload("u1", "e1", file(big)))
                .isInstanceOfSatisfying(InvalidPhotoException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        verify(storage, never()).store(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void storedFilesAreRemovedIfMetadataCannotBeSaved() {
        when(photos.save(any())).thenThrow(new IllegalStateException("base indisponible"));
        assertThatThrownBy(() -> service.upload("u1", "e1", file(TestImages.jpeg(100, 100))))
                .isInstanceOf(IllegalStateException.class);
        verify(storage).delete("f1");
        verify(storage).delete("t1");
    }

    @Test
    void contentOfAnUnknownPhotoIsNotFound() {
        when(photos.findByIdAndEventIdAndOwnerId("p1", "e1", "u2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.content("u2", "e1", "p1", false)).isInstanceOf(PhotoNotFoundException.class);
    }

    private static MockMultipartFile named(String name, byte[] data) {
        return new MockMultipartFile("files", name, "image/jpeg", data);
    }

    @Test
    void batchStoresEachValidFileAndReportsTheRejectedOnesWithoutStoppingTheOthers() {
        byte[] big = new byte[(int) DataSize.ofMegabytes(1).toBytes() + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        BatchUploadResponse result = service.uploadAll("u1", "e1", java.util.List.of(
                named("a.jpg", TestImages.jpeg(100, 80)),
                named("anim.gif", "GIF89a....".getBytes()),
                named("b.jpg", TestImages.jpeg(60, 40)),
                named("enorme.jpg", big),
                named("vide.jpg", new byte[0])));

        assertThat(result.photos()).extracting(PhotoResponse::width).containsExactly(100, 60);
        assertThat(result.rejected()).extracting(BatchUploadResponse.RejectedFile::index).containsExactly(1, 3, 4);
        assertThat(result.rejected()).extracting(BatchUploadResponse.RejectedFile::filename)
                .containsExactly("anim.gif", "enorme.jpg", "vide.jpg");
        assertThat(result.rejected()).extracting(BatchUploadResponse.RejectedFile::status).containsExactly(415, 413, 400);
        assertThat(result.rejected().get(1).message()).isEqualTo("Photo trop volumineuse (maximum 1 Mo)");
        verify(photos, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void batchOfMoreThanTenFilesIsRefusedAsAWhole() {
        java.util.List<org.springframework.web.multipart.MultipartFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            files.add(named(i + ".jpg", TestImages.jpeg(10, 10)));
        }
        assertThatThrownBy(() -> service.uploadAll("u1", "e1", files))
                .isInstanceOfSatisfying(InvalidPhotoException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Trop de photos : 10 au maximum par envoi");
                });
        verify(storage, never()).store(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyBatchIsRefusedAndUnknownEventIsNotFound() {
        assertThatThrownBy(() -> service.uploadAll("u1", "e1", java.util.List.of()))
                .isInstanceOfSatisfying(InvalidPhotoException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(PhotoService.EMPTY));
        assertThatThrownBy(() -> service.uploadAll("u1", "e1", null)).isInstanceOf(InvalidPhotoException.class);
        assertThatThrownBy(() -> service.uploadAll("u2", "e1", java.util.List.of(named("a.jpg", TestImages.jpeg(10, 10)))))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void storageFailureOfOneFileIsReportedAndTheNextFilesAreStillStored() {
        when(photos.save(any()))
                .thenThrow(new IllegalStateException("base indisponible"))
                .thenAnswer(inv -> inv.getArgument(0));
        BatchUploadResponse result = service.uploadAll("u1", "e1",
                java.util.List.of(named("a.jpg", TestImages.jpeg(10, 10)), named("b.jpg", TestImages.jpeg(20, 10))));

        assertThat(result.photos()).extracting(PhotoResponse::width).containsExactly(20);
        assertThat(result.rejected()).singleElement().satisfies(r -> {
            assertThat(r.index()).isZero();
            assertThat(r.status()).isEqualTo(500);
            assertThat(r.message()).isEqualTo(PhotoService.STORE_FAILED);
        });
    }
}
