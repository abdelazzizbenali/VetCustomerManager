package dev.parent.scanner;

import dev.parent.config.AppConfig;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.util.List;

/**
 * Gemini-inspired pre-processing for low-quality laptop webcams:
 * reduce resolution, boost contrast, ROI crop, TRY_HARDER.
 * Applied before ZXing; also used for live preview so the user sees
 * exactly what the decoder sees.
 */
public final class ImageFilters {

    private ImageFilters() {}

    // ------------------------------------------------------------------ public API

    /** Preview: filtered but NOT cropped — draws ROI rectangle if enabled. */
    public static BufferedImage applyForPreview(BufferedImage src) {
        AppConfig c = AppConfig.get();
        return applyForPreview(src,
                c.cameraFilterBrightness(),
                c.cameraFilterContrast(),
                c.cameraFilterSaturation(),
                c.cameraFilterSharpness(),
                c.cameraFilterGrayscale(),
                c.cameraFilterInvert(),
                c.cameraFilterDownscale(),
                c.cameraFilterRoiEnabled(),
                c.cameraFilterRoiSize());
    }

    /** Decode: filtered + ROI cropped + downscaled. */
    public static BufferedImage applyForDecode(BufferedImage src) {
        AppConfig c = AppConfig.get();
        return applyForDecode(src,
                c.cameraFilterBrightness(),
                c.cameraFilterContrast(),
                c.cameraFilterSaturation(),
                c.cameraFilterSharpness(),
                c.cameraFilterGrayscale(),
                c.cameraFilterInvert(),
                c.cameraFilterDownscale(),
                c.cameraFilterRoiEnabled(),
                c.cameraFilterRoiSize());
    }

    /** Tuning preview with live slider values (not yet saved). drawRoi=true draws rectangle, false crops. */
    public static BufferedImage applyWithParams(BufferedImage src,
                                                int brightness, int contrast, int saturation,
                                                int sharpness, boolean grayscale, boolean invert,
                                                boolean downscale, boolean roiEnabled, int roiSize,
                                                boolean drawRoi) {
        BufferedImage out = applyCore(src, brightness, contrast, saturation, sharpness, grayscale, invert, downscale);
        if (roiEnabled) {
            if (drawRoi) out = drawRoiRect(out, roiSize);
            else out = cropRoi(out, roiSize);
        }
        return out;
    }

    public static BufferedImage applyForPreview(BufferedImage src,
                                                int brightness, int contrast, int saturation,
                                                int sharpness, boolean grayscale, boolean invert,
                                                boolean downscale, boolean roiEnabled, int roiSize) {
        return applyWithParams(src, brightness, contrast, saturation, sharpness, grayscale, invert, downscale, roiEnabled, roiSize, true);
    }

    public static BufferedImage applyForDecode(BufferedImage src,
                                               int brightness, int contrast, int saturation,
                                               int sharpness, boolean grayscale, boolean invert,
                                               boolean downscale, boolean roiEnabled, int roiSize) {
        BufferedImage out = applyCore(src, brightness, contrast, saturation, sharpness, grayscale, invert, downscale);
        if (roiEnabled) out = cropRoi(out, roiSize);
        return out;
    }

    // ------------------------------------------------------------------ core

    private static BufferedImage applyCore(BufferedImage src,
                                           int brightness, int contrast, int saturation,
                                           int sharpness, boolean grayscale, boolean invert,
                                           boolean downscale) {
        if (src == null) return null;
        BufferedImage img = src;

        // 1. Downscale first to reduce work for later ops (Gemini: 640x480)
        if (downscale) {
            img = downscaleIfNeeded(img, 640);
        }

        // 2. Brightness / Contrast via RescaleOp (fast, hardware-accel)
        if (brightness != 0 || contrast != 100) {
            float scale = contrast / 100f;
            float offset = brightness; // -100..100 maps directly to 0-255 offset
            try {
                // Ensure we have a compatible type for RescaleOp (avoid TYPE_CUSTOM)
                if (img.getType() == BufferedImage.TYPE_CUSTOM) {
                    BufferedImage tmp = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
                    Graphics2D g = tmp.createGraphics();
                    g.drawImage(img, 0, 0, null);
                    g.dispose();
                    img = tmp;
                }
                float[] scales = {scale, scale, scale};
                float[] offsets = {offset, offset, offset};
                // Handle different band counts (GRAY vs RGB)
                int bands = img.getRaster().getNumBands();
                if (bands == 1) {
                    scales = new float[]{scale};
                    offsets = new float[]{offset};
                } else if (bands == 4) {
                    scales = new float[]{scale, scale, scale, 1f};
                    offsets = new float[]{offset, offset, offset, 0f};
                }
                RescaleOp op = new RescaleOp(scales, offsets, null);
                BufferedImage dest = op.filter(img, null);
                if (dest != null) img = dest;
            } catch (Throwable ignored) {}
        }

        // 3. Saturation (HSB per-pixel, only if != 100)
        if (saturation != 100) {
            float factor = saturation / 100f;
            try {
                img = adjustSaturation(img, factor);
            } catch (Throwable ignored) {}
        }

        // 4. Sharpness via ConvolveOp
        if (sharpness != 0) {
            try {
                img = sharpen(img, sharpness / 100f);
            } catch (Throwable ignored) {}
        }

        // 5. Grayscale
        if (grayscale) {
            try {
                ColorConvertOp op = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null);
                BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                op.filter(img, gray);
                // Convert back to 3-byte for ZXing BufferedImageLuminanceSource compatibility
                BufferedImage rgb = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(gray, 0, 0, null);
                g.dispose();
                img = rgb;
            } catch (Throwable ignored) {}
        }

        // 6. Invert
        if (invert) {
            try {
                byte[] table = new byte[256];
                for (int i = 0; i < 256; i++) table[i] = (byte) (255 - i);
                LookupOp op = new LookupOp(new ByteLookupTable(0, table), null);
                img = op.filter(img, null);
            } catch (Throwable ignored) {}
        }

        return img;
    }

    // ------------------------------------------------------------------ helpers

    private static BufferedImage downscaleIfNeeded(BufferedImage src, int maxWidth) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxWidth) return src;
        double ratio = (double) maxWidth / w;
        int nw = maxWidth;
        int nh = (int) Math.round(h * ratio);
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static BufferedImage adjustSaturation(BufferedImage src, float factor) {
        int w = src.getWidth(), h = src.getHeight();
        // Work on a copy to avoid mutating original
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                float[] hsb = Color.RGBtoHSB(r, g, b, null);
                hsb[1] = Math.max(0f, Math.min(1f, hsb[1] * factor));
                int nrgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
                out.setRGB(x, y, nrgb);
            }
        }
        return out;
    }

    private static BufferedImage sharpen(BufferedImage src, float strength) {
        // strength 0..2.0, 1.0 = normal sharpen
        float f = strength; // 0..2
        float center = 1 + 4 * f;
        float edge = -f;
        float[] kernelData = {
                0,    edge, 0,
                edge, center, edge,
                0,    edge, 0
        };
        Kernel kernel = new Kernel(3, 3, kernelData);
        ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
        try {
            BufferedImage out = op.filter(src, null);
            return out != null ? out : src;
        } catch (Throwable t) {
            return src;
        }
    }

    private static BufferedImage cropRoi(BufferedImage src, int roiSizePercent) {
        int w = src.getWidth(), h = src.getHeight();
        // ROI is central rectangle; for EAN we keep it horizontally wide but vertically narrow
        // Use width = roiSize% of frame, height = roiSize% * 0.45 (thin horizontal band) when roiSize >=70
        int roiW = Math.max(80, w * roiSizePercent / 100);
        int roiH;
        if (roiSizePercent >= 70) {
            // thin horizontal band — Gemini tip for EAN on cylinders
            roiH = Math.max(60, (int) (h * roiSizePercent / 100 * 0.45));
        } else {
            roiH = Math.max(80, h * roiSizePercent / 100);
        }
        int x = (w - roiW) / 2;
        int y = (h - roiH) / 2;
        // Clamp
        x = Math.max(0, Math.min(x, w - roiW));
        y = Math.max(0, Math.min(y, h - roiH));
        try {
            return src.getSubimage(x, y, roiW, roiH);
        } catch (Throwable t) {
            return src;
        }
    }

    private static BufferedImage drawRoiRect(BufferedImage src, int roiSizePercent) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        int w = src.getWidth(), h = src.getHeight();
        int roiW = Math.max(80, w * roiSizePercent / 100);
        int roiH = (roiSizePercent >= 70) ? Math.max(60, (int) (h * roiSizePercent / 100 * 0.45)) : Math.max(80, h * roiSizePercent / 100);
        int x = (w - roiW) / 2;
        int y = (h - roiH) / 2;
        // Semi-transparent fill + bright border
        g.setColor(new Color(0, 255, 0, 40));
        g.fillRect(x, y, roiW, roiH);
        g.setColor(new Color(0, 255, 0));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(x, y, roiW, roiH);
        // Corner accents
        int c = 18;
        g.setStroke(new BasicStroke(3f));
        // top-left
        g.drawLine(x, y, x + c, y); g.drawLine(x, y, x, y + c);
        // top-right
        g.drawLine(x + roiW, y, x + roiW - c, y); g.drawLine(x + roiW, y, x + roiW, y + c);
        // bottom-left
        g.drawLine(x, y + roiH, x + c, y + roiH); g.drawLine(x, y + roiH, x, y + roiH - c);
        // bottom-right
        g.drawLine(x + roiW, y + roiH, x + roiW - c, y + roiH); g.drawLine(x + roiW, y + roiH, x + roiW, y + roiH - c);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ ZXing hints helper

    public static List<com.google.zxing.BarcodeFormat> parseFormats(String csv) {
        if (csv == null || csv.isBlank()) return List.of(
                com.google.zxing.BarcodeFormat.CODE_128, com.google.zxing.BarcodeFormat.CODE_39,
                com.google.zxing.BarcodeFormat.EAN_13, com.google.zxing.BarcodeFormat.EAN_8,
                com.google.zxing.BarcodeFormat.UPC_A, com.google.zxing.BarcodeFormat.UPC_E,
                com.google.zxing.BarcodeFormat.QR_CODE);
        try {
            return java.util.Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try { return com.google.zxing.BarcodeFormat.valueOf(s.trim().toUpperCase()); }
                        catch (Exception e) { return null; }
                    })
                    .filter(f -> f != null)
                    .toList();
        } catch (Throwable t) {
            return List.of(com.google.zxing.BarcodeFormat.QR_CODE, com.google.zxing.BarcodeFormat.CODE_128);
        }
    }
}
