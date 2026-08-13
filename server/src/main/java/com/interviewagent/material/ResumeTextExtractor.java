package com.interviewagent.material;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
class ResumeTextExtractor {
    static final int MAX_STORED_CHARS = 40_000;

    ExtractedText extract(String contentType, byte[] content) throws IOException {
        String text = switch (contentType) {
            case "application/pdf" -> pdf(content);
            case "application/msword" -> doc(content);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> docx(content);
            default -> throw new IOException("不支持的简历文件类型。");
        };
        String normalized = text.replace('\u0000', ' ').replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\\n\\n").trim();
        if (normalized.isBlank()) throw new IOException("未提取到可用文本；扫描件需要先补充可复制的文字版简历。");
        boolean truncated = normalized.length() > MAX_STORED_CHARS;
        return new ExtractedText(truncated ? normalized.substring(0, MAX_STORED_CHARS) : normalized, truncated);
    }

    private String pdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String doc(byte[] content) throws IOException {
        try (WordExtractor extractor = new WordExtractor(new HWPFDocument(new ByteArrayInputStream(content)))) {
            return extractor.getText();
        }
    }

    private String docx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content)); XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    record ExtractedText(String text, boolean truncated) {}
}
