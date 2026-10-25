package com.syx.demo.llm.answer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析模型输出尾部的元信息行，并把系统的免责声明补上。
 *
 * <p>模型输出是不可信输入，这里的处理原则是<b>解析失败一律往外保守的方向兜底</b>：
 * 置信度解析不出来就记"低"，来源解析不出来就记"无"，绝不因为"没解析到"而默认成"高"。
 * 同时把 {@code [META]} 行从正文里剥掉 —— 那是给系统看的，留在正文里会干扰医生阅读。
 *
 * <p><b>【META 行长什么样】</b>模型实际返回的原文大致是：
 * <pre>
 * 咬伤后立即用肥皂水冲洗伤口至少 15 分钟，再用碘伏消毒，不要缝合……
 * 建议 24 小时内接种狂犬疫苗。[1][2]
 *
 * [META] confidence=高; basis=指南; sources=1,2
 * </pre>
 * 这行是 {@code output_constraint.st} 要求模型输出的，有三个用途：
 * 前端显示置信度、系统核对引用编号是否越界、流式时作为"正文结束"的标记。
 *
 * <p><b>【阅读导航】</b>先看 {@link #parse}（剥 META + 三个正则），
 * 再看 {@link #ensureDisclaimer}（系统兜底补免责声明）。
 * 整类的原则是：<b>解析失败一律往保守方向兜底</b>——解析不到置信度记"低"，绝不默认成"高"。
 */
public final class AnswerMetaParser {

    /** 固定免责声明。由系统保证存在，不依赖模型是否照做。 */
    public static final String DISCLAIMER =
            "本回答由 AI 基于院内知识库自动生成，仅供临床参考，不能替代执业医师的面诊判断与最终决策。";

    private static final Pattern META_LINE = Pattern.compile("\\[META\\][^\\n\\r]*");
    private static final Pattern CONFIDENCE = Pattern.compile("confidence\\s*[=:：]\\s*([^;；,，\\s]+)");
    private static final Pattern BASIS = Pattern.compile("basis\\s*[=:：]\\s*([^;；,，\\s]+)");
    private static final Pattern SOURCES = Pattern.compile("sources\\s*[=:：]\\s*([^;；\\n\\r]*)");

    private AnswerMetaParser() {
    }

    /** 解析结果。 */
    public record Parsed(String body, String confidence, String basis, List<Integer> sources) {
    }

    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Parsed("", "低", "无", List.of());
        }
        String body = raw.trim();
        String confidence = "低";
        String basis = "无";
        List<Integer> sources = new ArrayList<>();

        Matcher meta = META_LINE.matcher(body);
        String metaText = null;
        StringBuilder stripped = new StringBuilder();
        int lastEnd = 0;
        while (meta.find()) {
            // 只认最后一条元信息，前面的括掉（模型有时会先写一段草稿再补元信息）
            metaText = meta.group();
            stripped.append(body, lastEnd, meta.start());
            lastEnd = meta.end();
        }
        stripped.append(body, lastEnd, body.length());

        if (metaText != null) {
            body = stripped.toString().replaceAll("\\n{3,}", "\n\n").trim();
            confidence = firstGroup(CONFIDENCE, metaText, "低");
            basis = firstGroup(BASIS, metaText, "无");
            sources = parseSources(metaText);
        }

        return new Parsed(body, normalizeConfidence(confidence), basis, sources);
    }

    /** 把模型可能写出的同义说法归一到"高/中/低"。 */
    private static String normalizeConfidence(String value) {
        if (value == null) {
            return "低";
        }
        String v = value.trim();
        if (v.contains("高") || v.equalsIgnoreCase("high")) {
            return "高";
        }
        if (v.contains("中") || v.equalsIgnoreCase("medium") || v.equalsIgnoreCase("mid")) {
            return "中";
        }
        if (v.contains("低") || v.equalsIgnoreCase("low")) {
            return "低";
        }
        return "低";
    }

    private static String firstGroup(Pattern p, String text, String fallback) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private static List<Integer> parseSources(String metaText) {
        List<Integer> result = new ArrayList<>();
        String raw = firstGroup(SOURCES, metaText, "");
        if (raw.isBlank() || raw.equalsIgnoreCase("none") || raw.contains("无")) {
            return result;
        }
        for (String part : raw.split("[,，、\\s]+")) {
            String cleaned = part.replaceAll("[^0-9]", "");
            if (!cleaned.isEmpty()) {
                try {
                    result.add(Integer.parseInt(cleaned));
                }
                catch (NumberFormatException ignored) {
                    // 忽略无法解析的编号
                }
            }
        }
        return result;
    }

    /**
     * 确保正文末尾带免责声明。模型可能漏抄、也可能抄错，这里以系统文案为准补齐。
     */
    public static String ensureDisclaimer(String body) {
        String text = body == null ? "" : body.trim();
        if (text.contains(DISCLAIMER)) {
            return text;
        }
        // 模型自己写了一句相似但不同的免责声明时，不要再叠加第二句
        if (text.contains("不能替代") && text.contains("参考")) {
            return text;
        }
        return text + "\n\n" + DISCLAIMER;
    }
}
