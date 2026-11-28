package com.example.hadaq.Service;

import com.example.hadaq.Model.PrivacyResult;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/*
 * Privacy: hide the faces of people in uploaded photos before anything is stored (challenge criterion).
 * Port of hadaq_api.privacy. Faces are found with YuNet from the OpenCV Zoo (models/face_detection_yunet_2023mar.onnx,
 * MIT licence next to it), on the CPU, no network. Each face is pixelated and then blurred so it cannot be recovered.
 * The score threshold is below the usual 0.9: when in doubt we hide. Re-encoding also drops EXIF (GPS position).
 */
@Slf4j
@Service
public class PrivacyService {

    private static final String MODEL = "models/face_detection_yunet_2023mar.onnx";
    private static final float SCORE_THRESHOLD = 0.6f;
    private static final float NMS_THRESHOLD = 0.3f;
    private static final int DETECT_MAX_SIDE = 2048;  // detect on a downscaled copy
    private static final double PAD = 0.3;            // grow each face box by this share on every side
    private static final int JPEG_QUALITY = 95;

    private record Box(int x, int y, int w, int h) {
    }

    private FaceDetectorYN detector;   // keeps its input size as state: used under the lock only
    private boolean unavailable;

    // undecodable bytes come back unchanged with checked = false
    public PrivacyResult protect(byte[] data, String suffix) {
        if (!ensureLoaded()) {
            log.error("Face hiding unavailable (OpenCV could not load); photo stored without the privacy check");
            return new PrivacyResult(data, false, 0);
        }
        Mat image = Imgcodecs.imdecode(new MatOfByte(data), Imgcodecs.IMREAD_COLOR);
        if (image.empty()) {
            log.warn("Upload is not a decodable image; stored as is, privacy not checked");
            return new PrivacyResult(data, false, 0);
        }
        List<Box> boxes = detectFaces(image);
        hide(image, boxes);
        MatOfByte encoded = new MatOfByte();
        boolean ok = ".png".equals(suffix)
                ? Imgcodecs.imencode(".png", image, encoded)
                : Imgcodecs.imencode(".jpg", image, encoded, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY));
        if (!ok) {
            throw new IllegalStateException("could not re-encode the photo after hiding faces");
        }
        if (!boxes.isEmpty()) {
            log.info("Hid {} faces", boxes.size());
        }
        return new PrivacyResult(encoded.toArray(), true, boxes.size());
    }

    private synchronized boolean ensureLoaded() {
        if (detector != null) {
            return true;
        }
        if (unavailable) {
            return false;
        }
        try {
            nu.pattern.OpenCV.loadLocally();
            Path model = Files.createTempFile("yunet-", ".onnx");
            model.toFile().deleteOnExit();
            try (InputStream in = new ClassPathResource(MODEL).getInputStream()) {
                Files.copy(in, model, StandardCopyOption.REPLACE_EXISTING);
            }
            detector = FaceDetectorYN.create(model.toString(), "", new Size(320, 320), SCORE_THRESHOLD, NMS_THRESHOLD, 5000);
            return true;
        } catch (Throwable exception) {
            log.error("Could not load OpenCV / YuNet", exception);
            unavailable = true;
            return false;
        }
    }

    // face boxes in full-image pixels
    private List<Box> detectFaces(Mat image) {
        int height = image.rows();
        int width = image.cols();
        double scale = Math.min(1.0, DETECT_MAX_SIDE / (double) Math.max(height, width));
        Mat small = image;
        if (scale < 1) {
            small = new Mat();
            Imgproc.resize(image, small, new Size(Math.round(width * scale), Math.round(height * scale)), 0, 0, Imgproc.INTER_AREA);
        }
        Mat faces = new Mat();
        synchronized (this) {
            detector.setInputSize(new Size(small.cols(), small.rows()));
            detector.detect(small, faces);
        }
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < faces.rows(); i++) {
            boxes.add(new Box(
                    (int) Math.round(faces.get(i, 0)[0] / scale),
                    (int) Math.round(faces.get(i, 1)[0] / scale),
                    (int) Math.round(faces.get(i, 2)[0] / scale),
                    (int) Math.round(faces.get(i, 3)[0] / scale)));
        }
        return boxes;
    }

    // pixelate then blur each box (grown by PAD), in place
    private static void hide(Mat image, List<Box> boxes) {
        int height = image.rows();
        int width = image.cols();
        for (Box b : boxes) {
            int x0 = Math.max(0, (int) (b.x() - b.w() * PAD));
            int y0 = Math.max(0, (int) (b.y() - b.h() * PAD));
            int x1 = Math.min(width, (int) (b.x() + b.w() * (1 + PAD)));
            int y1 = Math.min(height, (int) (b.y() + b.h() * (1 + PAD)));
            if (x1 - x0 < 2 || y1 - y0 < 2) {
                continue;
            }
            Mat region = image.submat(y0, y1, x0, x1);
            Mat blocks = new Mat();
            Imgproc.resize(region, blocks, new Size(Math.max(1, (x1 - x0) / 12), Math.max(1, (y1 - y0) / 12)), 0, 0, Imgproc.INTER_AREA);
            Mat pixelated = new Mat();
            Imgproc.resize(blocks, pixelated, new Size(x1 - x0, y1 - y0), 0, 0, Imgproc.INTER_NEAREST);
            Mat blurred = new Mat();
            Imgproc.GaussianBlur(pixelated, blurred, new Size(0, 0), Math.max(x1 - x0, y1 - y0) / 10.0);
            blurred.copyTo(region);
        }
    }
}
