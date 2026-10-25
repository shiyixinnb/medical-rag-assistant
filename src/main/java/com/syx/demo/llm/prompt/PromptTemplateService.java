package com.syx.demo.llm.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板服务：从 {@code classpath:prompts/*.st} 加载模板并做变量替换。
 *
 * <p><b>为什么不用 Spring AI 的 {@code PromptTemplate}？</b>
 * M7 的 {@code PromptTemplate} 底层是 StringTemplate（ST）引擎，模板里的
 * <code>{ }</code> 会被当作表达式语法。而医疗场景的 Prompt 里天然充满花括号 ——
 * JSON 输出格式示例、剂量区间、集合写法都会踩到 ST 的解析规则，
 * 一旦某段资料里恰好出现 {@code {}} 就会直接抛模板解析异常，属于线上不可控风险。
 *
 * <p>所以这里只保留一个极小的替换语义：<b>{@code {{key}}}</b> 双花括号占位符，
 * 替换后如果有占位符没被替换（说明调用方漏传变量或模板写错名字），
 * 立刻抛异常而不是把 {@code {{rag_context}}} 这种字面量发给模型 ——
 * 后者会让模型收到噪声指令，回答质量莫名其妙地下降，而且极难排查。
 *
 * <p><b>【阅读导航】</b>对外只有三个"build"方法，其中最重要的是 {@link #buildSystemPrompt}，
 * 它把三份模板按顺序拼成最终的 system 文本：
 * <pre>
 *   system_role.st        角色设定 + 本意图的额外提示（intent_hint）
 *   rag_context.st        检索到的资料块（带 [1][2] 编号）+ 数据域说明
 *   output_constraint.st  输出格式（引用编号、置信度、免责声明）
 * </pre>
 * 三份都必须渲染成功才算数 —— 缺变量直接抛异常，不把半成品发给模型。
 *
 * <p>模板文件在 {@code src/main/resources/prompts/}，改完调 {@link #clearCache()} 生效
 * （缓存用的是 {@code ConcurrentHashMap}，进程内只读一次磁盘）。
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    /** 占位符：{{key}}，key 允许字母、数字、下划线、点。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    /** 模板名 -> 原文。模板是静态资源，进程内缓存一次即可。 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 模板常量

    /** 角色设定：急诊专家 + 输出约束（分意图变体）。 */
    public static final String TPL_SYSTEM_ROLE = "system_role";
    /** RAG 上下文注入块。 */
    public static final String TPL_RAG_CONTEXT = "rag_context";
    /** 输出格式约束（引用来源、置信度、免责声明）。 */
    public static final String TPL_OUTPUT_CONSTRAINT = "output_constraint";
    /** 信息不足时的澄清追问。 */
    public static final String TPL_CLARIFY = "clarify";
    /** 检索不到依据时的拒答。 */
    public static final String TPL_NO_EVIDENCE = "no_evidence";

    public static final String VAR_INTENT_LABEL = "intent_label";
    public static final String VAR_INTENT_HINT = "intent_hint";
    public static final String VAR_DOMAIN_SCOPE = "domain_scope";
    public static final String VAR_SNIPPETS = "snippets";
    public static final String VAR_SNIPPET_COUNT = "snippet_count";
    public static final String VAR_DISCLAIMER = "disclaimer";
    public static final String VAR_QUESTION = "question";
    public static final String VAR_PREVIOUS_QUESTION = "previous_question";
    public static final String VAR_PATIENT_CONTEXT = "patient_context";

    // ------------------------------------------------------------------ 核心 API

    /**
     * 组装最终的 system 文本：角色设定 + 上下文注入 + 输出约束。
     *
     * @param intentLabel 意图中文名，例如"急诊分诊"
     * @param intentHint  针对该意图的额外提示，可为空
     * @param ragBlock    已经渲染好的资料块（无资料时由调用方传入"未检索到"的文案）
     * @param domainScope 数据域描述，例如"院内公共知识库（指南/药品说明/诊疗规范）"
     */
    public String buildSystemPrompt(String intentLabel, String intentHint,
                                    String ragBlock, String domainScope, String disclaimer) {
        Map<String, String> roleVars = new LinkedHashMap<>();
        roleVars.put(VAR_INTENT_LABEL, intentLabel);
        roleVars.put(VAR_INTENT_HINT, intentHint == null || intentHint.isBlank() ? "（无特殊提示）" : intentHint);

        // RAG 上下文块本身也要参与角色模板的渲染，所以先渲染它
        Map<String, String> ctxVars = new LinkedHashMap<>();
        ctxVars.put(VAR_DOMAIN_SCOPE, domainScope);
        ctxVars.put(VAR_SNIPPETS, ragBlock);
        ctxVars.put(VAR_SNIPPET_COUNT, String.valueOf(countSnippets(ragBlock)));
        String contextSection = render(TPL_RAG_CONTEXT, ctxVars);

        Map<String, String> constraintVars = new LinkedHashMap<>();
        constraintVars.put(VAR_DISCLAIMER, disclaimer);
        String constraintSection = render(TPL_OUTPUT_CONSTRAINT, constraintVars);

        String role = render(TPL_SYSTEM_ROLE, roleVars);

        return role + "\n\n" + contextSection + "\n\n" + constraintSection;
    }

    /** 渲染澄清追问的用户提示。 */
    public String buildClarifyPrompt(String previousQuestion, String question) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(VAR_PREVIOUS_QUESTION, nullToDash(previousQuestion));
        vars.put(VAR_QUESTION, nullToDash(question));
        return render(TPL_CLARIFY, vars);
    }

    /** 渲染"检索不到依据"的提示词（让模型用自然语言解释，而不是我们硬编码一段冷冰冰的文案）。 */
    public String buildNoEvidencePrompt(String question, String domainScope) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(VAR_QUESTION, nullToDash(question));
        vars.put(VAR_DOMAIN_SCOPE, domainScope);
        return render(TPL_NO_EVIDENCE, vars);
    }

    /** 取模板原文（带缓存）。 */
    public String load(String name) {
        return cache.computeIfAbsent(name, n -> {
            String path = "prompts/" + n + ".st";
            try {
                ClassPathResource resource = new ClassPathResource(path);
                if (!resource.exists()) {
                    throw new IllegalStateException("Prompt 模板不存在：" + path);
                }
                try (var in = resource.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            catch (IOException e) {
                throw new UncheckedIOException("读取 Prompt 模板失败：" + path, e);
            }
        });
    }

    /**
     * 渲染模板。变量缺失会抛异常 —— 宁可启动/首次调用就炸，也不要静默地给模型发半成品指令。
     */
    public String render(String name, Map<String, String> variables) {
        String template = load(name);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        List<String> missing = new java.util.ArrayList<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables == null ? null : variables.get(key);
            if (value == null) {
                missing.add(key);
                value = "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Prompt 模板 " + name + " 有以下变量未提供：" + missing
                    + "；已提供的变量：" + (variables == null ? "无" : variables.keySet()));
        }
        return sb.toString();
    }

    /** 清理模板缓存，改完 .st 文件后（配合 devtools 热重载）可以调这个。 */
    public void clearCache() {
        cache.clear();
        log.info("[Prompt] 模板缓存已清空");
    }

    private String nullToDash(String s) {
        return s == null || s.isBlank() ? "（无）" : s;
    }

    /** 粗略统计资料块里的条目数，仅用于提示模型"一共给了几条资料"。 */
    private int countSnippets(String ragBlock) {
        if (ragBlock == null) {
            return 0;
        }
        int count = 0;
        for (String line : ragBlock.split("\n")) {
            if (line.trim().startsWith("[")) {
                count++;
            }
        }
        return count;
    }
}
