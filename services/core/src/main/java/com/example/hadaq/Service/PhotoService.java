package com.example.hadaq.Service;

import com.example.hadaq.Api.ApiException;
import com.example.hadaq.DTOOut.PhotoDTOOut;
import com.example.hadaq.Entity.Photo;
import com.example.hadaq.Enum.EventSource;
import com.example.hadaq.Enum.EventType;
import com.example.hadaq.Enum.PhotoStatus;
import com.example.hadaq.Enum.Provenance;
import com.example.hadaq.Mapper.DTOMapper;
import com.example.hadaq.Model.PrivacyResult;
import com.example.hadaq.Repository.PhotoRepository;
import com.example.hadaq.Util.Ids;
import com.example.hadaq.Util.Numbers;
import com.example.hadaq.Util.Times;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class PhotoService {

    public record PhotoFile(FileSystemResource resource, MediaType mediaType) {
    }

    private final PhotoRepository photoRepository;
    private final RegistryService registryService;
    private final PrivacyService privacyService;
    private final LiveService liveService;
    private final EventService eventService;
    private final Path photoDir;
    private final String publicUrl;
    private final boolean privacyBlur;

    public PhotoService(PhotoRepository photoRepository, RegistryService registryService, PrivacyService privacyService,
                        LiveService liveService, EventService eventService,
                        @Value("${hadaq.photo-dir:./data/photos}") String photoDir,
                        @Value("${hadaq.public-url:http://localhost:8000}") String publicUrl,
                        @Value("${hadaq.privacy-blur:true}") boolean privacyBlur) throws IOException {
        this.photoRepository = photoRepository;
        this.registryService = registryService;
        this.privacyService = privacyService;
        this.liveService = liveService;
        this.eventService = eventService;
        this.photoDir = Path.of(photoDir);
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.privacyBlur = privacyBlur;
        Files.createDirectories(this.photoDir);
    }

    // POST /photos (camera simulator, field surveyor): faces hidden first, then stored, pushed live, new_photo event
    public PhotoDTOOut upload(MultipartFile file, String cameraId, String buildingId, String capturedAt,
                              String provenanceValue, String sequence, Integer frame) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ApiException("file is required");
        }
        Provenance provenance = provenanceValue == null || provenanceValue.isBlank()
                ? Provenance.SYNTHETIC : Provenance.fromValue(provenanceValue);
        return store(file.getBytes(), file.getOriginalFilename(), cameraId, buildingId, capturedAt, provenance, sequence, frame);
    }

    // the same, for photos made inside the app (the demo camera)
    public PhotoDTOOut store(byte[] original, String filename, String cameraId, String buildingId, String capturedAt,
                             Provenance provenance, String sequence, Integer frame) throws IOException {
        registryService.check(buildingId);
        String suffix = suffixOf(filename);
        byte[] data = original;
        boolean checked = false;
        int facesHidden = 0;
        if (privacyBlur) {
            PrivacyResult privacy = privacyService.protect(data, suffix);
            data = privacy.data();
            checked = privacy.checked();
            facesHidden = privacy.facesHidden();
            if (checked && !".png".equals(suffix)) {
                suffix = ".jpg";  // re-encoded as JPEG
            }
        }

        Photo photo = new Photo();
        photo.setPhotoId(Ids.newId("ph"));
        photo.setCameraId(cameraId);
        photo.setBuildingId(buildingId);
        photo.setCapturedAt(Times.parseOrNow(capturedAt));
        photo.setProvenance(provenance);
        photo.setSequence(sequence);
        photo.setFrame(frame);
        photo.setStatus(PhotoStatus.NEW);
        photo.setPrivacyChecked(checked);
        photo.setFacesHidden(facesHidden);
        Path path = photoDir.resolve(photo.getPhotoId() + suffix);
        Files.write(path, data);
        photo.setFilePath(path.toString());
        photo.setFileUrl(publicUrl + "/photos/" + photo.getPhotoId() + "/file");
        photoRepository.save(photo);

        PhotoDTOOut dto = DTOMapper.toDTO(photo);
        liveService.broadcast(LiveService.PHOTO, dto);

        Map<String, Object> privacy = new LinkedHashMap<>();
        privacy.put("checked", checked);
        privacy.put("faces_hidden", facesHidden);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("photo_id", photo.getPhotoId());
        payload.put("camera_id", cameraId);
        payload.put("sequence", sequence);
        payload.put("frame", frame);
        payload.put("privacy", privacy);
        eventService.handle(EventService.create(EventType.NEW_PHOTO, buildingId, EventSource.API, provenance, payload), true);
        return dto;
    }

    // with status: the work queue, oldest first. Without: newest first
    public List<PhotoDTOOut> list(String buildingId, String status, int limit) {
        Limit max = Limit.of(Numbers.clamp(limit, 1, 200));
        List<Photo> photos = status == null || status.isBlank()
                ? photoRepository.findByBuildingIdOrderByCapturedAtDesc(buildingId, max)
                : photoRepository.findByBuildingIdAndStatusOrderByCapturedAtAsc(buildingId, PhotoStatus.fromValue(status), max);
        return photos.stream().map(DTOMapper::toDTO).toList();
    }

    public PhotoFile file(String photoId) {
        Photo photo = photoRepository.findById(photoId).orElseThrow(() -> ApiException.notFound("Photo not found"));
        FileSystemResource resource = new FileSystemResource(photo.getFilePath());
        if (!resource.exists()) {
            throw ApiException.notFound("Photo not found");
        }
        MediaType type = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return new PhotoFile(resource, type);
    }

    private static String suffixOf(String filename) {
        String name = filename == null || filename.isBlank() ? "photo.jpg" : filename;
        int dot = name.lastIndexOf('.');
        String suffix = dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
        // only simple image-like suffixes end up on disk
        return suffix.matches("^\\.[a-z0-9]{1,5}$") ? suffix : ".jpg";
    }
}
