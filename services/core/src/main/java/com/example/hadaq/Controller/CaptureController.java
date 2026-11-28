package com.example.hadaq.Controller;

import com.example.hadaq.Service.CaptureService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

// v2 captures (contracts/README.md "v2"): the surveyor app uploads, the AI worker posts results back
@RestController
@RequestMapping("/api/v2/captures")
@RequiredArgsConstructor
public class CaptureController {

    private final CaptureService captureService;

    // multipart: file (JPEG) + meta (CaptureMeta JSON). 202 CaptureAccepted; a repeated client_uuid answers duplicate = true
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart(value = "file", required = false) MultipartFile file,
                                    @RequestPart("meta") String meta) throws IOException {
        return ResponseEntity.accepted().body(captureService.accept(file, meta));
    }

    @GetMapping("/{captureId}")
    public ResponseEntity<?> get(@PathVariable String captureId) {
        return ResponseEntity.ok(captureService.get(captureId));
    }

    @GetMapping("/{captureId}/file")
    public ResponseEntity<Resource> file(@PathVariable String captureId) {
        CaptureService.CaptureFile file = captureService.file(captureId, false);
        return ResponseEntity.ok().contentType(file.mediaType()).body(file.resource());
    }

    @GetMapping("/{captureId}/rectified")
    public ResponseEntity<Resource> rectified(@PathVariable String captureId) {
        CaptureService.CaptureFile file = captureService.file(captureId, true);
        return ResponseEntity.ok().contentType(file.mediaType()).body(file.resource());
    }

    // sends a failed capture to the AI worker again (409 for any other status)
    @PostMapping("/{captureId}/retry")
    public ResponseEntity<?> retry(@PathVariable String captureId) {
        return ResponseEntity.ok(captureService.retry(captureId));
    }

    // the AI worker's callback. multipart: results (CaptureResults JSON) + rectified (JPEG, optional)
    @PostMapping(value = "/{captureId}/results", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> results(@PathVariable String captureId,
                                     @RequestPart("results") String results,
                                     @RequestPart(value = "rectified", required = false) MultipartFile rectified) throws IOException {
        return ResponseEntity.ok(captureService.results(captureId, results, rectified));
    }
}
