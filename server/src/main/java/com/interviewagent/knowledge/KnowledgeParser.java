package com.interviewagent.knowledge;

import com.interviewagent.material.ResumeTextExtractor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeParser {
    private static final int MAX_CHARS = 200_000;
    private static final int MAX_SEGMENTS = 2_000;
    private final ResumeTextExtractor words;

    public KnowledgeParser(ResumeTextExtractor words) { this.words = words; }

    public List<Segment> parse(String extension, byte[] bytes) {
        try {
            List<Segment> segments = switch (extension) {
                case "md" -> paragraphs(markdown(bytes), true);
                case "doc", "docx" -> {
                    String type = extension.equals("doc") ? "application/msword" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    var extracted = words.extract(type, bytes);
                    if (extracted.truncated()) throw new IllegalArgumentException("Word 正文过长，请拆分后上传。");
                    yield paragraphs(extracted.text(), false);
                }
                case "xlsx" -> workbook(bytes);
                default -> throw new IllegalArgumentException("仅支持 MD、XLSX、DOC、DOCX 知识库文件。");
            };
            if (segments.isEmpty()) throw new IllegalArgumentException("文件中没有可导入的文字或题目。");
            if (segments.stream().anyMatch(s -> s.content().length() > 2_000 || s.originalAnswer().length() > 2_500 || s.answer().length() > 2_500 || s.reminder().length() > 500))
                throw new IllegalArgumentException("单个题目或段落过长，请拆分后上传。");
            if (segments.size() > MAX_SEGMENTS || segments.stream().mapToInt(s -> s.content().length() + s.originalAnswer().length() + s.answer().length()).sum() > MAX_CHARS)
                throw new IllegalArgumentException("知识库文件内容过长，请拆分后上传。");
            return segments;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("无法解析知识库文件，请检查格式或文件是否加密。", error); }
    }

    private static String markdown(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static List<Segment> paragraphs(String text, boolean markdown) {
        List<Segment> result = new ArrayList<>();
        String heading = "正文";
        StringBuilder chunk = new StringBuilder();
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (markdown) value = value.replaceAll("!\\[[^]]*]\\([^)]*\\)", "").trim();
            if (value.isBlank()) continue;
            if (markdown && value.matches("#{1,6}\\s+.+")) {
                flush(result, chunk, heading);
                heading = value.replaceFirst("^#{1,6}\\s+", "");
                continue;
            }
            if (chunk.length() + value.length() > 900) flush(result, chunk, heading);
            if (value.length() > 900) {
                for (int start = 0; start < value.length(); start += 900)
                    result.add(new Segment("PASSAGE", heading, value.substring(start, Math.min(start + 900, value.length())), "", "", ""));
            } else chunk.append(value).append('\n');
        }
        flush(result, chunk, heading);
        return result;
    }

    private static void flush(List<Segment> result, StringBuilder chunk, String heading) {
        if (!chunk.isEmpty()) result.add(new Segment("PASSAGE", heading, chunk.toString().trim(), "", "", ""));
        chunk.setLength(0);
    }

    private static List<Segment> workbook(byte[] bytes) throws IOException {
        List<Segment> result = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (var sheet : book) {
                int header = -1, questionColumn = -1, originalColumn = -1, answerColumn = -1, reminderColumn = -1, idColumn = -1;
                for (Row row : sheet) {
                    if (header < 0) {
                        for (Cell cell : row) {
                            String label = value(cell, formatter).replaceAll("[\\s（）()：:]", "");
                            if (label.equals("问题")) questionColumn = cell.getColumnIndex();
                            if (label.contains("我的答案") && (label.contains("原始") || label.contains("待完善"))) originalColumn = cell.getColumnIndex();
                            if (label.contains("优化后标准答案")) answerColumn = cell.getColumnIndex();
                            if (label.contains("面试提醒")) reminderColumn = cell.getColumnIndex();
                            if (label.contains("原序号")) idColumn = cell.getColumnIndex();
                        }
                        if (questionColumn >= 0) { header = row.getRowNum(); continue; }
                        continue;
                    }
                    Cell questionCell = row.getCell(questionColumn);
                    String question = value(questionCell, formatter);
                    if (question.isBlank() && questionCell != null && questionCell.getCellType() == CellType.FORMULA)
                        throw new IllegalArgumentException("工作表「" + sheet.getSheetName() + "」第 " + (row.getRowNum() + 1) + " 行问题公式没有可读取的缓存值。");
                    if (question.isBlank()) continue;
                    if (idColumn >= 0 && !value(row.getCell(idColumn), formatter).matches("\\d+")) continue;
                    String answer = answerColumn < 0 ? "" : value(row.getCell(answerColumn), formatter);
                    String original = originalColumn < 0 ? "" : value(row.getCell(originalColumn), formatter);
                    if (original.matches("\\d+")) original = "";
                    String reminder = reminderColumn < 0 ? "" : value(row.getCell(reminderColumn), formatter);
                    String location = sheet.getSheetName() + "!" + (row.getRowNum() + 1);
                    result.add(new Segment("QUESTION", location, question, original, answer, reminder));
                }
                if (header < 0) throw new IllegalArgumentException("工作表「" + sheet.getSheetName() + "」缺少「问题」表头。");
            }
        }
        return result;
    }

    private static String value(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) {
            return switch (cell.getCachedFormulaResultType()) {
                case STRING -> cell.getStringCellValue().trim();
                case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString()).trim();
                default -> "";
            };
        }
        return formatter.formatCellValue(cell).trim();
    }

    public record Segment(String kind, String location, String content, String originalAnswer, String answer, String reminder) {}
}
