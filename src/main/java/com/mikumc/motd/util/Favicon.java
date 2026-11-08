package com.mikumc.motd.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
 * 服务器图标的加载与规范化：任意尺寸的图片会被复制到客户端要求的 64x64 画布，
 * 再重新编码为 PNG。该流程只在（重）加载配置时执行，不在 ping 路径上。
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
    public static String load(String location, Path dataDirectory) throws IOException {
        if (location == null || location.isBlank() || location.equalsIgnoreCase("none")) {
            return null;
        }

        byte[] bytes;
        if (location.startsWith(DATA_URL_PREFIX)) {
            bytes = decodeDataUrl(location);
        } else {
            Path file = Path.of(location);
            bytes = Files.readAllBytes(file.isAbsolute() ? file : dataDirectory.resolve(location));
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Not a valid image: " + location);
        }
        return DATA_URL_PREFIX + Base64.getEncoder().encodeToString(encode(toIcon(image)));
    }

    /** data URL 解析：非法 base64 统一包装为 IOException，交给调用方按「无图标」降级。 */
    private static byte[] decodeDataUrl(String location) throws IOException {
        try {
            return Base64.getDecoder().decode(location.substring(DATA_URL_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed base64 data URL", e);
        }
    }

    /**
     * 统一复制到 64x64 ARGB 画布：既完成缩放，也保证 PNG 写入器支持该图像类型
     * （16 位 PNG 会读出 TYPE_CUSTOM，直接交给 ImageIO.write 会静默写出 0 字节）。
     */
    private static BufferedImage toIcon(BufferedImage source) {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
        graphics.dispose();
        return icon;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", imageOut)) {
                // 走到这里说明没有可用的 PNG 写入器：宁可按「无图标」降级，也不要产出空 data URL
                throw new IOException("No PNG writer available");
            }
        }
        return out.toByteArray();
    }
}
