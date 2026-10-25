package com.syx.demo.llm.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一次检索的完整结果，含"查了哪些域、被拒了哪些域"的审计信息。
 *
 * <p>把"被拒绝的域"也留在结果里，是为了让拒答理由说得清楚：
 * 是对面真的没资料，还是因为没授权所以压根没查？这两种情况的处理方式完全不同 ——
 * 前者应该引导用户换个问法，后者应该提示用户补充授权。
 */
public final class RetrievalResult {

    private final List<RetrievedSnippet> snippets;
    /** 实际检索过的数据域。 */
    private final Set<KnowledgeDomain> searchedDomains;
    /** 请求了但未授权的数据域。 */
    private final Set<KnowledgeDomain> deniedDomains;
    /** 过程说明，例如"患者域未授权，已跳过"、"丢弃了 2 条越域片段"。 */
    private final List<String> notes;
    /** 被安全校验拦下的越域片段数量（正常应恒为 0，非 0 说明隔离层出问题了）。 */
    private final int blockedViolations;

    public RetrievalResult(List<RetrievedSnippet> snippets, Set<KnowledgeDomain> searchedDomains,
                           Set<KnowledgeDomain> deniedDomains, List<String> notes, int blockedViolations) {
        this.snippets = snippets == null ? new ArrayList<>() : snippets;
        this.searchedDomains = searchedDomains == null ? new LinkedHashSet<>() : searchedDomains;
        this.deniedDomains = deniedDomains == null ? new LinkedHashSet<>() : deniedDomains;
        this.notes = notes == null ? new ArrayList<>() : notes;
        this.blockedViolations = blockedViolations;
    }

    public static RetrievalResult empty(String note) {
        List<String> notes = new ArrayList<>();
        if (note != null) {
            notes.add(note);
        }
        return new RetrievalResult(new ArrayList<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), notes, 0);
    }

    public List<RetrievedSnippet> getSnippets() {
        return snippets;
    }

    public Set<KnowledgeDomain> getSearchedDomains() {
        return searchedDomains;
    }

    public Set<KnowledgeDomain> getDeniedDomains() {
        return deniedDomains;
    }

    public List<String> getNotes() {
        return notes;
    }

    public int getBlockedViolations() {
        return blockedViolations;
    }

    public boolean isEmpty() {
        return snippets.isEmpty();
    }

    /** 是否所有请求的域都被拒了（这种情况连模型都不用调）。 */
    public boolean allDenied() {
        return snippets.isEmpty() && !deniedDomains.isEmpty() && searchedDomains.isEmpty();
    }

    /** 是否有未授权的域被跳过 —— 拒答文案里要提到它，否则医生会以为是知识库没资料。 */
    public boolean hasDeniedDomain() {
        return !deniedDomains.isEmpty();
    }

    /** 供 prompt 使用的数据域描述。 */
    public String describeScope() {
        if (searchedDomains.isEmpty()) {
            return deniedDomains.stream().map(KnowledgeDomain::getLabel)
                    .collect(Collectors.joining("、", "", "（均未授权，本轮未检索）"));
        }
        return searchedDomains.stream().map(KnowledgeDomain::describeForPrompt)
                .collect(Collectors.joining("；"));
    }

    /**
     * 渲染成注入 Prompt 的资料块。无资料时返回一句明确的"未检索到"，
     * 而不是空字符串 —— 空字符串会让模型以为"上面还有内容"，进而开始编。
     */
    public String renderContextBlock(int maxCharsPerSnippet) {
        if (snippets.isEmpty()) {
            StringBuilder sb = new StringBuilder("（本轮未检索到可用资料。");
            if (hasDeniedDomain()) {
                sb.append("其中部分数据域因未授权被跳过：")
                        .append(deniedDomains.stream().map(KnowledgeDomain::getLabel)
                                .collect(Collectors.joining("、")))
                        .append("。");
            }
            sb.append("请按「无依据」的要求回答，不要凭记忆补充具体数值。）");
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        // 片段前面的 [1][2] 编号来自 RetrievedSnippet.render()，序号是检索层统一重排过的
        // （按相关度倒序）。模型被要求用这个编号标注出处；收尾阶段 buildAnswer 会校验
        // 模型引用的编号是否越界 —— 越界就是幻觉的明确信号。
        for (RetrievedSnippet s : snippets) {
            sb.append(s.render(maxCharsPerSnippet, true)).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 供响应返回的引用清单。 */
    public List<RetrievedSnippet> top(int n) {
        return snippets.size() <= n ? snippets : snippets.subList(0, n);
    }
}
