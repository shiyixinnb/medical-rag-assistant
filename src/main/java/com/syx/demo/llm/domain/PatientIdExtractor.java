package com.syx.demo.llm.domain;

import com.syx.demo.llm.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从自然语言问题里抽出「显式点名的患者标识」。
 *
 * <p>为什么需要它：数据域隔离保证了"检索不到别的机构的资料"，但还有一种情况需要确定性处理 ——
 * 用户拿 A 机构的授权去问 B 机构的患者（{@code tenantId=HOSP-A} + "患者 P-9001 的诊断"）。
 * 光靠"检索不到"时，模型可能会说"资料里没有 P-9001"，也可能顺着上下文拿本机构别的患者
 * 的资料去硬答，这两种行为都不够可控。
 *
 * <p>做法：把问题里出现的患者标识抽出来交给检索层收敛（多带一层 {@code patientId == 'xxx'} 过滤），
 * 查不到就复用既有的「已授权但无该患者资料 → 拒答」分支 —— 拒答理由清晰，且不依赖模型判断。
 *
 * <p>识别用的正则可配置（{@code app.llm.domain.patient-id-pattern}），因为各院患者编号规则不同；
 * 默认正则只认 {@code P-1001} 这类"字母段 + 连字符 + 数字段"，不会误伤 {@code HOSP-A} 这类院区标识。
 */
@Component
public class PatientIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(PatientIdExtractor.class);

    private final Pattern pattern;

    public PatientIdExtractor(LlmProperties properties) {
        String regex = properties.getDomain().getPatientIdPattern();
        Pattern compiled;
        try {
            compiled = Pattern.compile(regex);
        }
        catch (RuntimeException ex) {
            // 配错了不能让整条链路挂掉，退化为"不识别患者标识"
            log.warn("[Domain] patient-id-pattern 无法编译，已关闭患者标识识别：{}（{}）", regex, ex.getMessage());
            compiled = null;
        }
        this.pattern = compiled;
    }

    /** 抽出问题里出现的所有患者标识（去重、保持出现顺序）。 */
    public Set<String> extract(String question) {
        Set<String> found = new LinkedHashSet<>();
        if (pattern == null || question == null || question.isBlank()) {
            return found;
        }
        Matcher m = pattern.matcher(question);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    /**
     * 抽出唯一一个患者标识；出现 0 个或多个时返回 {@code null}（含义是"不做患者维度收敛"）。
     *
     * <p>为什么不取第一个：问题里同时出现两个患者编号，多半是在对比或核对，
     * 这时把检索收窄到其中一个反而会答错，不如退回"按机构维度检索"。
     */
    public String extractSingle(String question) {
        Set<String> all = extract(question);
        if (all.size() != 1) {
            if (all.size() > 1) {
                log.info("[Domain] 问题中出现 {} 个患者标识 {}，本轮不做患者维度收敛", all.size(), all);
            }
            return null;
        }
        return all.iterator().next();
    }
}
