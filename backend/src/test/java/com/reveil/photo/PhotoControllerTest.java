package com.reveil.photo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reveil.auth.TokenService;
import com.reveil.config.ApiExceptionHandler;
import com.reveil.config.SecurityConfig;
import com.reveil.event.EventNotFoundException;
import com.reveil.session.SessionService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PhotoController.class)
@Import({ApiExceptionHandler.class, SecurityConfig.class})
class PhotoControllerTest {

    private static final MockMultipartFile FILE = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PhotoService photoService;
    @MockitoBean
    TokenService tokenService;
    @MockitoBean
    SessionService sessionService;

    @BeforeEach
    void authenticate() {
        when(tokenService.verify("good")).thenReturn(Optional.of(new TokenService.AccessToken("u1", "a@b.co", "s1")));
        when(sessionService.isActive("s1")).thenReturn(true);
    }

    private static PhotoResponse photo() {
        return new PhotoResponse("p1", "e1", "image/jpeg", 3, 2048, 1536, Instant.parse("2026-11-26T10:00:00Z"),
                "/api/events/e1/photos/p1/content", "/api/events/e1/photos/p1/thumbnail");
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        mvc.perform(multipart("/api/events/e1/photos").file(FILE)).andExpect(status().isUnauthorized());
        verify(photoService, never()).upload(any(), any(), any());
    }

    @Test
    void uploadReturns201WithThePhotoAndItsUrls() throws Exception {
        when(photoService.upload(eq("u1"), eq("e1"), any())).thenReturn(photo());
        mvc.perform(multipart("/api/events/e1/photos").file(FILE).header("Authorization", "Bearer good"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("p1"))
                .andExpect(jsonPath("$.eventId").value("e1"))
                .andExpect(jsonPath("$.width").value(2048))
                .andExpect(jsonPath("$.thumbnailUrl").value("/api/events/e1/photos/p1/thumbnail"));
    }

    @Test
    void refusedFileIsReportedWithItsStatusAndMessage() throws Exception {
        when(photoService.upload(eq("u1"), eq("e1"), any()))
                .thenThrow(new InvalidPhotoException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ImageProcessor.UNSUPPORTED));
        mvc.perform(multipart("/api/events/e1/photos").file(FILE).header("Authorization", "Bearer good"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(ImageProcessor.UNSUPPORTED))
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void multipartOverTheServletLimitReturns413() throws Exception {
        when(photoService.upload(eq("u1"), eq("e1"), any()))
                .thenThrow(new org.springframework.web.multipart.MaxUploadSizeExceededException(11 * 1024 * 1024));
        mvc.perform(multipart("/api/events/e1/photos").file(FILE).header("Authorization", "Bearer good"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void uploadToAnUnknownEventReturns404() throws Exception {
        when(photoService.upload(eq("u1"), eq("nope"), any())).thenThrow(new EventNotFoundException());
        mvc.perform(multipart("/api/events/nope/photos").file(FILE).header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsTheGallery() throws Exception {
        when(photoService.list("u1", "e1")).thenReturn(List.of(photo()));
        mvc.perform(get("/api/events/e1/photos").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("p1"))
                .andExpect(jsonPath("$[0].url").value("/api/events/e1/photos/p1/content"));
    }

    @Test
    void thumbnailIsServedAsJpegWithPrivateCache() throws Exception {
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
        when(photoService.content("u1", "e1", "p1", true)).thenReturn(new PhotoService.Content(
                new PhotoStorage.StoredFile(new ByteArrayInputStream(bytes), bytes.length), "image/jpeg"));
        mvc.perform(get("/api/events/e1/photos/p1/thumbnail").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void contentRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/events/e1/photos/p1/content")).andExpect(status().isUnauthorized());
    }

    @Test
    void batchUploadPassesAllTheFilesAndReturnsSavedAndRejectedOnes() throws Exception {
        when(photoService.uploadAll(eq("u1"), eq("e1"), any())).thenReturn(new BatchUploadResponse(List.of(photo()),
                List.of(new BatchUploadResponse.RejectedFile(1, "anim.gif", 415, ImageProcessor.UNSUPPORTED))));
        MockMultipartFile a = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1});
        MockMultipartFile b = new MockMultipartFile("files", "anim.gif", "image/gif", new byte[]{2});

        mvc.perform(multipart("/api/events/e1/photos/batch").file(a).file(b).header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos[0].id").value("p1"))
                .andExpect(jsonPath("$.rejected[0].index").value(1))
                .andExpect(jsonPath("$.rejected[0].filename").value("anim.gif"))
                .andExpect(jsonPath("$.rejected[0].status").value(415))
                .andExpect(jsonPath("$.rejected[0].message").value(ImageProcessor.UNSUPPORTED));
        verify(photoService).uploadAll(eq("u1"), eq("e1"),
                org.mockito.ArgumentMatchers.argThat(files -> files != null && files.size() == 2));
    }

    @Test
    void batchUploadRequiresAuthenticationAndReportsARefusedBatch() throws Exception {
        MockMultipartFile a = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1});
        mvc.perform(multipart("/api/events/e1/photos/batch").file(a)).andExpect(status().isUnauthorized());
        when(photoService.uploadAll(eq("u1"), eq("e1"), any()))
                .thenThrow(new InvalidPhotoException(HttpStatus.BAD_REQUEST, "Trop de photos : 10 au maximum par envoi"));
        mvc.perform(multipart("/api/events/e1/photos/batch").file(a).header("Authorization", "Bearer good"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trop de photos : 10 au maximum par envoi"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/events/e1/photos/p1").header("Authorization", "Bearer good"))
                .andExpect(status().isNoContent());
        verify(photoService).delete("u1", "e1", "p1");
    }

    @Test
    void deleteRequiresAuthentication() throws Exception {
        mvc.perform(delete("/api/events/e1/photos/p1")).andExpect(status().isUnauthorized());
        verify(photoService, never()).delete(any(), any(), any());
    }

    @Test
    void deletingAnUnknownPhotoReturns404() throws Exception {
        doThrow(new PhotoNotFoundException()).when(photoService).delete("u1", "e1", "nope");
        mvc.perform(delete("/api/events/e1/photos/nope").header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Photo introuvable"));
    }
}
