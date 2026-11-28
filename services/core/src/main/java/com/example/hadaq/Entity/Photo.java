package com.example.hadaq.Entity;

import com.example.hadaq.Enum.PhotoStatus;
import com.example.hadaq.Enum.Provenance;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// a camera photo; the file itself is on disk (hadaq.photo-dir), faces already hidden
@Entity
@Table(name = "photos", indexes = {
        @Index(name = "idx_photo_building_captured", columnList = "building_id, captured_at"),
        @Index(name = "idx_photo_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
public class Photo {

    @Id
    @Column(name = "photo_id", length = 40)
    private String photoId;

    @Column(name = "camera_id", nullable = false, length = 20)
    private String cameraId;

    @Column(name = "building_id", nullable = false, length = 10)
    private String buildingId;

    @Column(name = "captured_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant capturedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provenance provenance;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "sequence_name", length = 60)
    private String sequence;

    private Integer frame;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PhotoStatus status = PhotoStatus.NEW;

    // privacy check: false when the upload was not a decodable image (stored unchanged)
    @Column(name = "privacy_checked", nullable = false)
    private boolean privacyChecked;

    @Column(name = "faces_hidden", nullable = false)
    private int facesHidden;
}
