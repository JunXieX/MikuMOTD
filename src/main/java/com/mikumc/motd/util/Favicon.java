package com.mikumc.motd.util;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;

/**
 * 服务器图标的加载与规范化：任意尺寸的 PNG 会被缩放到客户端要求的 64x64，
 * 并可按配置质量重新编码以压缩最终嵌入 MOTD JSON 的字节数。
 * 该流程只在（重）加载配置时执行，不在 ping 路径上。
 */
public final class Favicon {

    private static final String DATA_URL_PREFIX = "data:image/png;base64,";
    private static final int ICON_SIZE = 64;

    private Favicon() {
    }

    /**
     * @param location 配置项：data URL、文件路径（相对插件数据目录或绝对路径）、
     *                 空串/"none" 表示无图标
     * @return 嵌入 MOTD JSON 的 data URL；无图标返回 null
     */
    public static String load(String location, Path dataDirectory, double quality) throws IOException {
        if (location == null || location.isBlank() || location.equalsIgnoreCase("none")) {
            return null;
        }

        byte[] bytes;
        if (location.startsWith(DATA_URL_PREFIX)) {
            bytes = Base64.getDecoder().decode(location.substring(DATA_URL_PREFIX.length()));
        } else {
            Path file = Path.of(location);
            if (!file.isAbsolute()) {
                file = dataDirectory.resolve(location);
            }
            if (!Files.isReadable(file)) {
                throw new IOException("Icon file not readable: " + file);
            }
            bytes = Files.readAllBytes(file);
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Not a valid PNG image: " + location);
        }
        if (image.getWidth() != ICON_SIZE || image.getHeight() != ICON_SIZE) {
            image = scale(image, ICON_SIZE, ICON_SIZE);
        }
        if (quality >= 0.0D && quality < 1.0D) {
            bytes = recompress(image, quality);
        } else {
            bytes = encode(image);
        }

        return DATA_URL_PREFIX + Base64.getEncoder().encodeToString(bytes);
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            ImageIO.write(image, "png", imageOut);
        }
        return out.toByteArray();
    }

    private static byte[] recompress(BufferedImage image, double quality) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("png").next();
        var parameters = writer.getDefaultWriteParam();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality((float) quality);
            }
            writer.setOutput(imageOut);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
            writer.dispose();
        }
        return out.toByteArray();
    }
}
