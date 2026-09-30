package com.interviewagent.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.interviewagent.material.ResumeTextExtractor;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class KnowledgeParserTest {
    private final KnowledgeParser parser = new KnowledgeParser(new ResumeTextExtractor());

    @Test void markdownAndWordKeepRetrievableParagraphs() throws Exception {
        var markdown = parser.parse("md", "# React\n组件状态用于管理界面。\n## Hooks\nuseEffect 处理副作用。".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, markdown.size());
        assertEquals("React", markdown.get(0).location());
        assertEquals("Hooks", markdown.get(1).location());
        try (var word = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("组件状态与副作用");
            word.write(output);
            assertTrue(parser.parse("docx", output.toByteArray()).get(0).content().contains("组件状态"));
        }
        assertThrows(IllegalArgumentException.class, () -> parser.parse("md", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("md", new byte[] {(byte) 0xFF}));
    }

    @Test void spreadsheetKeepsQuestionsAnswersAndLocations() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            String[] names = {"S级-必考核心", "A级-高频重要", "B级-中频常见", "C级-低频补充"};
            int[] counts = {29, 28, 28, 20};
            for (int sheetIndex = 0; sheetIndex < names.length; sheetIndex++) {
                var sheet = book.createSheet(names[sheetIndex]);
                var heading = sheet.createRow(2);
                heading.createCell(0).setCellValue("原序号"); heading.createCell(1).setCellValue("问题");
                heading.createCell(2).setCellValue("我的答案（原始/待完善）"); heading.createCell(3).setCellValue("优化后标准答案"); heading.createCell(4).setCellValue("面试提醒");
                for (int i = 0; i < counts[sheetIndex]; i++) {
                    var row = sheet.createRow(i + 3);
                    row.createCell(0).setCellValue(i + 1); row.createCell(1).setCellValue("问题" + i + "？");
                    row.createCell(2).setCellValue(i == 0 ? "12" : "原始回答" + i);
                    row.createCell(3).setCellValue("参考答案" + i); row.createCell(4).setCellValue("提醒" + i);
                }
                var note = sheet.createRow(counts[sheetIndex] + 4);
                note.createCell(1).setCellValue("说明行，不是题目");
            }
            book.write(output); bytes = output.toByteArray();
        }
        var segments = parser.parse("xlsx", bytes);
        assertEquals(105, segments.size());
        assertEquals("S级-必考核心!4", segments.get(0).location());
        assertEquals("问题0？", segments.get(0).content());
        assertEquals("", segments.get(0).originalAnswer());
        assertEquals("原始回答1", segments.get(1).originalAnswer());
        assertEquals("参考答案0", segments.get(0).answer());
        assertEquals("提醒0", segments.get(0).reminder());
        assertEquals("C级-低频补充!23", segments.get(104).location());
    }
}
