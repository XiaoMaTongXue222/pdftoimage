package com.mzj.pdftoimage;

import android.graphics.Bitmap;

/**
 * 本地图像增强：全部基于 Android 原生 API，无第三方依赖、无网络、无次数限制。
 */
final class ImageEnhance {

    enum Mode {
        NONE("原样"),
        DOC("文档增强"),
        SHARP("强力锐化"),
        MONO("黑白二值");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private ImageEnhance() {
    }

    static Bitmap apply(Bitmap src, Mode mode, boolean removeWatermark, int whiteCut) {
        if (mode == Mode.NONE && !removeWatermark) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int total = w * h;
        int[] px = new int[total];
        src.getPixels(px, 0, w, 0, 0, w, h);

        byte[] gray = new byte[total];
        for (int i = 0; i < total; i++) {
            int c = px[i];
            gray[i] = (byte) (((c >> 16 & 0xFF) * 299
                    + (c >> 8 & 0xFF) * 587
                    + (c & 0xFF) * 114) / 1000);
        }

        byte[] mean = boxMean(gray, w, h, radius(w, h));

        for (int i = 0; i < total; i++) {
            int lum = gray[i] & 0xFF;
            int m = mean[i] & 0xFF;
            int target;
            switch (mode) {
                case MONO:
                    target = lum < m * 95 / 100 ? 0 : 255;
                    break;
                case SHARP:
                    target = clamp((int) (lum + 1.2f * (lum - m)));
                    break;
                case DOC:
                    target = clamp((int) (128 + (lum - m) * 1.8f));
                    break;
                default:
                    target = lum;
            }
            if (removeWatermark && target >= whiteCut) {
                target = 255;
            }
            px[i] = rescale(px[i], lum, target);
        }

        if (mode == Mode.MONO) {
            despeckle(px, w, h);
        }

        Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        dst.setPixels(px, 0, w, 0, 0, w, h);
        return dst;
    }

    private static int radius(int w, int h) {
        int r = Math.max(w, h) / 60;
        return Math.max(4, Math.min(r, 24));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int rescale(int argb, int lum, int target) {
        if (lum <= 0) {
            return 0xFF000000 | (target << 16) | (target << 8) | target;
        }
        float k = target / (float) lum;
        int r = clamp((int) ((argb >> 16 & 0xFF) * k));
        int g = clamp((int) ((argb >> 8 & 0xFF) * k));
        int b = clamp((int) ((argb & 0xFF) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 二值化后去掉孤立噪点：黑点邻域黑点少于 2 个则判为噪声。 */
    private static void despeckle(int[] px, int w, int h) {
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if ((px[i] & 0xFF) > 128) {
                    continue;
                }
                int black = 0;
                for (int dy = -1; dy <= 1 && black < 2; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        if ((px[i + dy * w + dx] & 0xFF) <= 128) {
                            black++;
                        }
                    }
                }
                if (black < 2) {
                    px[i] = 0xFFFFFFFF;
                }
            }
        }
    }

    /** 盒式局部均值，两趟滑动窗口，避免逐像素重算。 */
    private static byte[] boxMean(byte[] gray, int w, int h, int r) {
        byte[] rowAvg = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int off = y * w;
            int sum = 0;
            int cnt = 0;
            for (int x = 0; x <= r && x < w; x++) {
                sum += gray[off + x] & 0xFF;
                cnt++;
            }
            for (int x = 0; x < w; x++) {
                rowAvg[off + x] = (byte) (sum / cnt);
                int add = x + r + 1;
                int sub = x - r;
                if (add < w) {
                    sum += gray[off + add] & 0xFF;
                    cnt++;
                }
                if (sub >= 0) {
                    sum -= gray[off + sub] & 0xFF;
                    cnt--;
                }
            }
        }

        byte[] mean = new byte[w * h];
        for (int x = 0; x < w; x++) {
            int sum = 0;
            int cnt = 0;
            for (int y = 0; y <= r && y < h; y++) {
                sum += rowAvg[y * w + x] & 0xFF;
                cnt++;
            }
            for (int y = 0; y < h; y++) {
                mean[y * w + x] = (byte) (sum / cnt);
                int add = y + r + 1;
                int sub = y - r;
                if (add < h) {
                    sum += rowAvg[add * w + x] & 0xFF;
                    cnt++;
                }
                if (sub >= 0) {
                    sum -= rowAvg[sub * w + x] & 0xFF;
                    cnt--;
                }
            }
        }
        return mean;
    }
}
