package com.example.hadaq.Controller;

import com.example.hadaq.Service.DetectionService;
import com.example.hadaq.Service.PhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;
    private final DetectionService detectionService;

    // multipart: file, camera_id, building_id, captured_at, provenance, sequence, frame. Faces are hidden before storing
    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file,
                                    @RequestParam("camera_id") String cameraId,
                                    @RequestParam("building_id") String buildingId,
                                    @RequestParam(name = "captured_at", required = false) String capturedAt,
                                    @RequestParam(name = "provenance", required = false) String provenance,
                                    @RequestParam(name = "sequence", required = false) String sequence,
                                    @RequestParam(name = "frame", required = false) Integer frame) throws IOException {
        return ResponseEntity.ok(photoService.upload(file, cameraId, buildingId, capturedAt, provenance, sequence, frame));
    }

    // with status=new: the vision worker's queue (oldest first); without: newest first
    @GetMapping("/buildings/{buildingId}/photos")
    public ResponseEntity<?> list(@PathVariable String buildingId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(photoService.list(buildingId, status, limit));
    }

    @GetMapping("/photos/{photoId}/file")
    public ResponseEntity<Resource> file(@PathVariable String photoId) {
        PhotoService.PhotoFile file = photoService.file(photoId);
        return ResponseEntity.ok().contentType(file.mediaType()).body(file.resource());
    }

    // detections of the previous photo from the same camera (vision change detection)
    @GetMapping("/photos/{photoId}/previous-detections")
    public ResponseEntity<?> previousDetections(@PathVariable String photoId) {
        return ResponseEntity.ok(detectionService.previous(photoId));
    }
}
