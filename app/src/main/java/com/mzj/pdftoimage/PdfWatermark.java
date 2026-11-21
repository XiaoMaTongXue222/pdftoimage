package com.mzj.pdftoimage;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSStream;
import com.tom_roush.pdfbox.cos.COSString;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PDF 对象级水印处理：用内置 PdfBox-Android 解析 COS 对象树，找出水印对象并从文档里摘掉，
 * 另存为一份临时 PDF 供 PdfRenderer 渲染。库随 APK 打包，全程本地、无网络。
 *
 * PDF 规范没有 "Watermark" 对象类型，水印只能是普通绘制内容，因此按对象特征识别：
 *   1) /Subtype /Watermark 注解
 *   2) 名字含 watermark / 水印 / stamp / draft / 机密 的 OCG 图层
 *   3) 跨页复用的 Form XObject，且具备透明度组、低透明度 ExtGState 或浅灰填充内容
 */
final class PdfWatermark {

    private static final COSName K_OC_PROPERTIES = COSName.getPDFName("OCProperties");
    private static final COSName K_D = COSName.getPDFName("D");
    private static final COSName K_OFF = COSName.getPDFName("OFF");
    private static final COSName K_NAME = COSName.getPDFName("Name");
    private static final COSName K_GROUP = COSName.getPDFName("Group");
    private static final COSName K_S = COSName.getPDFName("S");
    private static final COSName K_TRANSPARENCY = COSName.getPDFName("Transparency");

    private PdfWatermark() {
    }

    /**
     * @return 去掉水印对象后的临时 PDF；没有找到任何水印对象（或改写结果不可渲染）时返回 null。
     */
    static File strip(Context ctx, Uri uri) throws Exception {
        File src = new File(ctx.getCacheDir(), "pdf_src.pdf");
        File out = new File(ctx.getCacheDir(), "pdf_no_watermark.pdf");
        src.delete();
        out.delete();

        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(src)) {
            copy(in, os);
        }

        try {
            try (PDDocument doc = PDDocument.load(src)) {
                if (!remove(doc)) {
                    return null;
                }
                doc.save(out);
            }

            // 改写后的 PDF 必须能被系统渲染器正常打开，否则丢弃，回落原文件。
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(out,
                    ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(pfd)) {
                renderer.openPage(0).close();
            } catch (Exception e) {
                out.delete();
                return null;
            }
            return out;
        } finally {
            src.delete();
        }
    }

    private static boolean remove(PDDocument doc) throws Exception {
        List<PDPage> pages = new ArrayList<>();
        for (PDPage p : doc.getPages()) {
            pages.add(p);
        }
        int pageCount = pages.size();
        boolean removed = false;

        // 1) /Subtype /Watermark 注解
        for (PDPage page : pages) {
            COSArray annots = asArray(page.getCOSObject().getDictionaryObject(COSName.ANNOTS));
            if (annots == null) {
                continue;
            }
            for (int i = annots.size() - 1; i >= 0; i--) {
                COSDictionary a = asDict(annots.get(i));
                if (a != null && "Watermark".equals(nameOf(a.getDictionaryObject(COSName.SUBTYPE)))) {
                    annots.remove(i);
                    removed = true;
                }
            }
        }

        // 2) OCG 水印图层：加入 /D /OFF 使其不可见
        COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
        COSDictionary ocProps = asDict(catalog.getDictionaryObject(K_OC_PROPERTIES));
        if (ocProps != null) {
            COSArray ocgs = asArray(ocProps.getDictionaryObject(COSName.OCGS));
            if (ocgs != null) {
                for (COSBase b : ocgs) {
                    COSDictionary ocg = asDict(b);
                    if (ocg == null) {
                        continue;
                    }
                    COSBase nb = ocg.getDictionaryObject(K_NAME);
                    String layer = nb instanceof COSString ? ((COSString) nb).getString() : "";
                    if (!isWatermarkName(layer)) {
                        continue;
                    }
                    COSDictionary d = asDict(ocProps.getDictionaryObject(K_D));
                    if (d == null) {
                        d = new COSDictionary();
                        ocProps.setItem(K_D, d);
                    }
                    COSArray off = asArray(d.getDictionaryObject(K_OFF));
                    if (off == null) {
                        off = new COSArray();
                        d.setItem(K_OFF, off);
                    }
                    off.add(b);
                    removed = true;
                }
            }
        }

        // 3) 跨页复用的 Form XObject
        Map<String, Integer> usage = new HashMap<>();
        Map<String, COSBase> sample = new HashMap<>();
        boolean transparentState = false;
        for (PDPage page : pages) {
            COSDictionary res = asDict(page.getCOSObject().getDictionaryObject(COSName.RESOURCES));
            if (res == null) {
                continue;
            }
            COSDictionary ext = asDict(res.getDictionaryObject(COSName.EXT_G_STATE));
            if (ext != null) {
                for (COSBase v : ext.getValues()) {
                    COSDictionary gs = asDict(v);
                    if (gs == null) {
                        continue;
                    }
                    if (gs.getFloat(COSName.CA, 1f) < 0.9f
                            || gs.getFloat(COSName.CA_NS, 1f) < 0.9f) {
                        transparentState = true;
                    }
                }
            }
            COSDictionary xo = asDict(res.getDictionaryObject(COSName.XOBJECT));
            if (xo == null) {
                continue;
            }
            Set<String> namesOnPage = new HashSet<>();
            for (COSName key : xo.keySet()) {
                String k = key.getName();
                namesOnPage.add(k);
                sample.putIfAbsent(k, xo.getDictionaryObject(key));
            }
            for (String k : namesOnPage) {
                usage.put(k, usage.getOrDefault(k, 0) + 1);
            }
        }

        int threshold = Math.max(2, (pageCount + 1) / 2);
        for (Map.Entry<String, Integer> e : usage.entrySet()) {
            if (e.getValue() < threshold) {
                continue;
            }
            COSStream form = asStream(sample.get(e.getKey()));
            if (form == null || !"Form".equals(nameOf(form.getDictionaryObject(COSName.SUBTYPE)))) {
                continue;
            }
            COSDictionary group = asDict(form.getDictionaryObject(K_GROUP));
            boolean transparentGroup = group != null
                    && K_TRANSPARENCY.equals(group.getDictionaryObject(K_S));
            if (!transparentState && !transparentGroup && !paleContent(form)) {
                continue;
            }
            for (PDPage page : pages) {
                COSDictionary res = asDict(page.getCOSObject().getDictionaryObject(COSName.RESOURCES));
                if (res == null) {
                    continue;
                }
                COSDictionary xo = asDict(res.getDictionaryObject(COSName.XOBJECT));
                if (xo == null) {
                    continue;
                }
                COSName key = COSName.getPDFName(e.getKey());
                COSStream st = asStream(xo.getDictionaryObject(key));
                if (st == null) {
                    continue;
                }
                // 清空 Form 内容而非删除资源名，页面里残留的 Do 指令仍然合法，只是画出空白。
                OutputStream os = st.createOutputStream();
                os.close();
                removed = true;
            }
        }
        return removed;
    }

    /** Form 内容流里是否设置了浅灰填充（0.6 <= 灰度 < 1），典型水印文字的着色方式。 */
    private static boolean paleContent(COSStream form) {
        try (InputStream is = form.getUnfilteredStream()) {
            String text = new String(readAll(is), StandardCharsets.ISO_8859_1);
            String[] t = text.trim().split("\\s+");
            for (int i = 0; i < t.length; i++) {
                if ("g".equals(t[i]) && i >= 1) {
                    float v = number(t[i - 1]);
                    if (v >= 0.6f && v < 1f) {
                        return true;
                    }
                }
                if ("rg".equals(t[i]) && i >= 3) {
                    float r = number(t[i - 3]);
                    float g = number(t[i - 2]);
                    float b = number(t[i - 1]);
                    if (r >= 0.6f && g >= 0.6f && b >= 0.6f && r < 1f) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static boolean isWatermarkName(String s) {
        if (s == null) {
            return false;
        }
        String t = s.toLowerCase(Locale.ROOT);
        return t.contains("watermark") || t.contains("水印") || t.contains("stamp")
                || t.contains("draft") || t.contains("confidential") || t.contains("机密");
    }

    private static float number(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static String nameOf(COSBase b) {
        if (b == null) {
            return "";
        }
        COSBase d = b.getCOSObject();
        return d instanceof COSName ? ((COSName) d).getName() : "";
    }

    private static COSDictionary asDict(COSBase b) {
        if (b == null) {
            return null;
        }
        COSBase d = b.getCOSObject();
        return d instanceof COSDictionary ? (COSDictionary) d : null;
    }

    private static COSArray asArray(COSBase b) {
        if (b == null) {
            return null;
        }
        COSBase d = b.getCOSObject();
        return d instanceof COSArray ? (COSArray) d : null;
    }

    private static COSStream asStream(COSBase b) {
        if (b == null) {
            return null;
        }
        COSBase d = b.getCOSObject();
        return d instanceof COSStream ? (COSStream) d : null;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }
}
