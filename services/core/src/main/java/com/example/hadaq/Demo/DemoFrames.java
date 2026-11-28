package com.example.hadaq.Demo;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/*
 * Synthetic fixed-camera frames for demos without a wall photograph (port of hadaq_vision.demo_frames, owner: Abdurhmn).
 * A 1600 x 1200 adobe wall at WALL_BBOX with older damage in every frame; the "damage" series adds a growing crack
 * (R2C5), a darkening wet base and a spreading plaster patch. Illustrative artwork, labelled synthetic everywhere.
 */
public final class DemoFrames {

    public static final int WIDTH = 1600;
    public static final int HEIGHT = 1200;
    public static final int[] WALL_BBOX = {80, 80, 1520, 1120};
    public static final int BASELINE_FRAMES = 4;
    public static final int DAMAGE_FRAMES = 8;

    // damage on the wall from the first frame, boxes in photo pixels (the mock detector reports the same boxes)
    public static final Map<String, int[]> STATIC_DAMAGE = Map.of(
            "holes", new int[]{320, 430, 420, 530},          // R3C2 small burrow holes
            "gullies", new int[]{1020, 370, 1110, 590},      // R3C6 rain gullies
            "salt", new int[]{1190, 980, 1330, 1100},        // R1C7 white salt bloom
            "parapet", new int[]{1180, 80, 1325, 175},       // R4C7 broken parapet edge
            "base_erosion", new int[]{630, 1060, 800, 1120}  // R1C4 eroded wall foot
    );

    private static final int[][] CRACK = {{900, 680}, {905, 700}, {896, 719}, {909, 740}, {900, 761},
            {910, 783}, {899, 808}, {907, 830}, {897, 855}, {904, 875}};

    private final BufferedImage base;
    private final List<byte[]> baseline = new ArrayList<>();
    private final List<byte[]> damage = new ArrayList<>();

    public DemoFrames() throws IOException {
        base = drawBase();
        for (int i = 1; i <= BASELINE_FRAMES; i++) {
            baseline.add(jpeg(base));
        }
        for (int i = 1; i <= DAMAGE_FRAMES; i++) {
            damage.add(jpeg(drawDamage(i)));
        }
    }

    // frame numbers start at 1
    public byte[] baseline(int frame) {
        return baseline.get(frame - 1);
    }

    public byte[] damage(int frame) {
        return damage.get(frame - 1);
    }

    private static BufferedImage drawBase() {
        Random rng = new Random(101);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(177, 157, 124));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        int x0 = WALL_BBOX[0], y0 = WALL_BBOX[1], x1 = WALL_BBOX[2], y1 = WALL_BBOX[3];
        g.setColor(new Color(177, 139, 99));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);

        // irregular adobe courses and fine grain, identical in every frame
        int row = 0;
        for (int y = y0; y < y1; y += 95, row++) {
            int offset = row % 2 == 1 ? -100 : 0;
            for (int x = x0 + offset; x < x1; x += 220) {
                int bx0 = Math.max(x0, x + 5), by0 = y + 5;
                int bx1 = Math.min(x1, x + 216), by1 = Math.min(y1, y + 90);
                if (bx0 >= bx1 || by0 >= by1) {
                    continue;
                }
                int tone = rng.nextInt(29) - 14;
                g.setColor(new Color(179 + tone, 139 + tone, 99 + tone));
                g.fillRect(bx0, by0, bx1 - bx0, by1 - by0);
                g.setColor(new Color(133, 105, 77));
                g.setStroke(new BasicStroke(3));
                g.drawLine(bx0, by1, bx1, by1);
                for (int i = 0; i < 85; i++) {
                    int px = bx0 + rng.nextInt(bx1 - bx0 + 1), py = by0 + rng.nextInt(by1 - by0 + 1);
                    int delta = rng.nextInt(43) - 22;
                    image.setRGB(Math.min(px, WIDTH - 1), Math.min(py, HEIGHT - 1),
                            new Color(Math.max(0, 176 + delta), Math.max(0, 137 + delta), Math.max(0, 99 + delta)).getRGB());
                }
            }
        }
        // the fine crack at R2C5 (baseline)
        g.setColor(new Color(70, 52, 39));
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        polyline(g, CRACK, 4);
        drawStaticDamage(g);
        g.dispose();
        return image;
    }

    // older damage visible in every frame: holes, rain gullies, salt, a broken parapet, an eroded base
    private static void drawStaticDamage(Graphics2D g) {
        Random rng = new Random(7);
        g.setStroke(new BasicStroke(2));
        for (int[] h : new int[][]{{345, 455, 11}, {378, 470, 8}, {360, 500, 13}, {398, 448, 7}, {392, 512, 9}}) {
            Ellipse2D hole = new Ellipse2D.Double(h[0] - h[2], h[1] - h[2] * 0.8, 2 * h[2], 1.6 * h[2]);
            g.setColor(new Color(48, 34, 24, 235));
            g.fill(hole);
            g.setColor(new Color(120, 95, 70, 200));
            g.draw(hole);
        }
        int[] gullies = STATIC_DAMAGE.get("gullies");
        for (int gx : new int[]{1035, 1066, 1094}) {
            List<int[]> pts = new ArrayList<>();
            for (int y = gullies[1]; y < gullies[3]; y += 22) {
                pts.add(new int[]{gx + rng.nextInt(9) - 4, y});
            }
            int[][] line = pts.toArray(new int[0][]);
            g.setColor(new Color(118, 88, 62, 210));
            g.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            polyline(g, line, line.length);
            int[][] light = pts.stream().map(p -> new int[]{p[0] + 4, p[1]}).toArray(int[][]::new);
            g.setColor(new Color(205, 170, 130, 120));
            g.setStroke(new BasicStroke(2));
            polyline(g, light, light.length);
        }
        int[] salt = STATIC_DAMAGE.get("salt");
        for (int i = 0; i < 26; i++) {
            int cx = salt[0] + 15 + rng.nextInt(salt[2] - salt[0] - 29);
            int cy = salt[1] + 15 + rng.nextInt(salt[3] - salt[1] - 24);
            int r = 8 + rng.nextInt(15);
            g.setColor(new Color(236, 228, 210, 90 + rng.nextInt(81)));
            g.fill(new Ellipse2D.Double(cx - r, cy - r * 0.7, 2 * r, 1.4 * r));
        }
        int[] p = STATIC_DAMAGE.get("parapet");
        Polygon parapet = new Polygon(
                new int[]{p[0], p[2], p[2] - 10, p[2] - 45, p[2] - 70, p[0] + 60, p[0] + 30, p[0] + 6},
                new int[]{p[1], p[1], p[1] + 40, p[1] + 62, p[3] - 8, p[3], p[1] + 70, p[1] + 30}, 8);
        g.setColor(new Color(177, 157, 124));
        g.fill(parapet);
        g.setColor(new Color(110, 82, 58, 230));
        g.setStroke(new BasicStroke(4));
        polyline(g, new int[][]{{p[2] - 10, p[1] + 40}, {p[2] - 45, p[1] + 62}, {p[2] - 70, p[3] - 8},
                {p[0] + 60, p[3]}, {p[0] + 30, p[1] + 70}}, 5);
        int[] e = STATIC_DAMAGE.get("base_erosion");
        Path2D edge = new Path2D.Double();
        edge.moveTo(e[0], e[3]);
        for (int x = e[0]; x <= e[2]; x += 14) {
            edge.lineTo(x, e[1] + 8 + (int) (14 * Math.abs(((x - e[0]) % 56) - 28) / 28.0));
        }
        edge.lineTo(e[2], e[3]);
        edge.closePath();
        g.setColor(new Color(104, 78, 56, 235));
        g.fill(edge);
    }

    // frame N of the damage series: the base darkens, the plaster patch grows, the crack extends
    private BufferedImage drawDamage(int index) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics();
        g.drawImage(base, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int x0 = WALL_BBOX[0], y1 = WALL_BBOX[3], x1 = WALL_BBOX[2];
        int wetHeight = 45 + index * 14;
        g.setColor(new Color(56, 40, 31, 20 + index * 5));
        g.fillRect(x0 + 5, y1 - wetHeight, x1 - x0 - 10, wetHeight - 5);
        Ellipse2D patch = new Ellipse2D.Double(285 - index * 5, 980 - index * 7, 90 + index * 12, 65 + index * 14);
        g.setColor(new Color(94, 74, 55, 175));
        g.fill(patch);
        g.setColor(new Color(130, 108, 81, 220));
        g.setStroke(new BasicStroke(4));
        g.draw(patch);
        g.setColor(new Color(51, 40, 32, 230));
        g.setStroke(new BasicStroke(3 + index / 3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        polyline(g, CRACK, Math.min(CRACK.length, 3 + index));
        g.dispose();
        return frame;
    }

    private static void polyline(Graphics2D g, int[][] points, int count) {
        Path2D path = new Path2D.Double();
        path.moveTo(points[0][0], points[0][1]);
        for (int i = 1; i < count; i++) {
            path.lineTo(points[i][0], points[i][1]);
        }
        g.draw(path);
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
