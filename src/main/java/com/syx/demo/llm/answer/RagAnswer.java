package com.syx.demo.llm.answer;

import com.syx.demo.llm.chat.ModelAttempt;
import com.syx.demo.llm.domain.RetrievedSnippet;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化的临床助手回答 —— 服务的出参，也是接口的响应体。
 *
 * <p>为什么不让模型直接返回一段文本就完事？因为医疗问答的可信度不来自"文字通顺"，
 * 而来自三件可核对的事：<b>依据从哪来、置信度多高、出错了谁负责</b>。
 * 所以这三项必须是结构化字段：
 *
 * <ul>
 *   <li>{@code citations} —— 由<b>系统</b>依据实际召回结果生成，不是让模型自己列出参考资料
 *       （模型会编造出看起来很合理的文献名，这是最危险的一类幻觉）；</li>
 *   <li>{@code confidence / basis} —— 由模型自评，但解析失败会兜底成"低"而不是"高"；</li>
 *   <li>{@code disclaimer} —— 固定文案，由系统保证一定存在，不依赖模型是否听话。</li>
 * </ul>
 */
public final class RagAnswer {

    private final String sessionId;
    private final String question;
    /** 回答正文（已剥离 [META] 行）。 */
    private final String answer;
    /** 置信度：高 / 中 / 低。 */
    private final String confidence;
    /** 依据类型：指南 / 资料 / 经验 / 无。 */
    private final String basis;
    /** 模型声明的引用编号（用于核对模型是否引用了不存在的资料）。 */
    private final List<Integer> declaredSources;
    /** 系统生成的权威引用清单。 */
    private final List<RetrievedSnippet> citations;
    /** 固定免责声明。 */
    private final String disclaimer;

    // ---- 决策与运行信息
    private final String intentLabel;
    private final String routeReason;
    private final String modelId;
    private final String routedModelId;
    private final boolean degraded;
    private final List<ModelAttempt> attempts;
    private final long costMs;

    // ---- 数据域与拒答
    private final String domainScope;
    private final boolean refused;
    private final String refuseReason;
    private final List<String> notes;

    private RagAnswer(Builder b) {
        this.sessionId = b.sessionId;
        this.question = b.question;
        this.answer = b.answer;
        this.confidence = b.confidence;
        this.basis = b.basis;
        this.declaredSources = b.declaredSources;
        this.citations = b.citations;
        this.disclaimer = b.disclaimer;
        this.intentLabel = b.intentLabel;
        this.routeReason = b.routeReason;
        this.modelId = b.modelId;
        this.routedModelId = b.routedModelId;
        this.degraded = b.degraded;
        this.attempts = b.attempts;
        this.costMs = b.costMs;
        this.domainScope = b.domainScope;
        this.refused = b.refused;
        this.refuseReason = b.refuseReason;
        this.notes = b.notes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 构造一个系统级拒答（不经模型），用于数据域未授权、无依据等场景。 */
    public static RagAnswer refuse(String sessionId, String question, String reason,
                                   String domainScope, List<String> notes, long costMs) {
        return builder()
                .sessionId(sessionId).question(question)
                .answer(reason)
                .confidence("无").basis("无")
                .declaredSources(new ArrayList<>()).citations(new ArrayList<>())
                .domainScope(domainScope)
                .refused(true).refuseReason(reason)
                .notes(notes == null ? new ArrayList<>() : notes)
                .attempts(new ArrayList<>())
                .costMs(costMs)
                .build();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public String getConfidence() {
        return confidence;
    }

    public String getBasis() {
        return basis;
    }

    public List<Integer> getDeclaredSources() {
        return declaredSources;
    }

    public List<RetrievedSnippet> getCitations() {
        return citations;
    }

    public String getDisclaimer() {
        return disclaimer;
    }

    public String getIntentLabel() {
        return intentLabel;
    }

    public String getRouteReason() {
        return routeReason;
    }

    public String getModelId() {
        return modelId;
    }

    public String getRoutedModelId() {
        return routedModelId;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public List<ModelAttempt> getAttempts() {
        return attempts;
    }

    public long getCostMs() {
        return costMs;
    }

    public String getDomainScope() {
        return domainScope;
    }

    public boolean isRefused() {
        return refused;
    }

    public String getRefuseReason() {
        return refuseReason;
    }

    public List<String> getNotes() {
        return notes;
    }

    public static final class Builder {
        private String sessionId;
        private String question;
        private String answer = "";
        private String confidence = "低";
        private String basis = "无";
        private List<Integer> declaredSources = new ArrayList<>();
        private List<RetrievedSnippet> citations = new ArrayList<>();
        private String disclaimer = "";
        private String intentLabel = "-";
        private String routeReason = "";
        private String modelId = "-";
        private String routedModelId = "-";
        private boolean degraded;
        private List<ModelAttempt> attempts = new ArrayList<>();
        private long costMs;
        private String domainScope = "";
        private boolean refused;
        private String refuseReason;
        private List<String> notes = new ArrayList<>();

        public Builder sessionId(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder question(String v) {
            this.question = v;
            return this;
        }

        public Builder answer(String v) {
            this.answer = v;
            return this;
        }

        public Builder confidence(String v) {
            this.confidence = v;
            return this;
        }

        public Builder basis(String v) {
            this.basis = v;
            return this;
        }

        public Builder declaredSources(List<Integer> v) {
            this.declaredSources = v;
            return this;
        }

        public Builder citations(List<RetrievedSnippet> v) {
            this.citations = v;
            return this;
        }

        public Builder disclaimer(String v) {
            this.disclaimer = v;
            return this;
        }

        public Builder intentLabel(String v) {
            this.intentLabel = v;
            return this;
        }

        public Builder routeReason(String v) {
            this.routeReason = v;
            return this;
        }

        public Builder modelId(String v) {
            this.modelId = v;
            return this;
        }

        public Builder routedModelId(String v) {
            this.routedModelId = v;
            return this;
        }

        public Builder degraded(boolean v) {
            this.degraded = v;
            return this;
        }

        public Builder attempts(List<ModelAttempt> v) {
            this.attempts = v;
            return this;
        }

        public Builder costMs(long v) {
            this.costMs = v;
            return this;
        }

        public Builder domainScope(String v) {
            this.domainScope = v;
            return this;
        }

        public Builder refused(boolean v) {
            this.refused = v;
            return this;
        }

        public Builder refuseReason(String v) {
            this.refuseReason = v;
            return this;
        }

        public Builder notes(List<String> v) {
            this.notes = v;
            return this;
        }

        public RagAnswer build() {
            return new RagAnswer(this);
        }
    }
}
