package com.syx.demo.llm.domain;

/**
 * 一条召回片段。编号（{@code [1]}）是<b>系统分配的</b>，不是模型编的 ——
 * 引用溯源必须可核对，所以编号在召回阶段就确定下来，模型只被允许"引用已有编号"。
 */
public final class RetrievedSnippet {

    private final int index;
    private final KnowledgeDomain domain;
    private final String docId;
    private final String title;
    private final String source;
    private final String content;
    private final Double score;
    private final String tenantId;
    private final String patientId;

    public RetrievedSnippet(int index, KnowledgeDomain domain, String docId, String title, String source,
                           String content, Double score, String tenantId, String patientId) {
        this.index = index;
        this.domain = domain;
        this.docId = docId;
        this.title = title;
        this.source = source;
        this.content = content;
        this.score = score;
        this.tenantId = tenantId;
        this.patientId = patientId;
    }

    public int getIndex() {
        return index;
    }

    public KnowledgeDomain getDomain() {
        return domain;
    }

    public String getDocId() {
        return docId;
    }

    public String getTitle() {
        return title;
    }

    public String getSource() {
        return source;
    }

    public String getContent() {
        return content;
    }

    public Double getScore() {
        return score;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getPatientId() {
        return patientId;
    }

    /** 渲染成一行注入 Prompt 的资料条目。 */
    public String render(int maxChars, boolean maskPatient) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(index).append("] ");
        sb.append(title == null || title.isBlank() ? "未命名资料" : title);
        sb.append("（来源：").append(source == null || source.isBlank() ? "未知" : source);
        sb.append("；数据域：").append(domain.getLabel());
        if (maskPatient && domain == KnowledgeDomain.PATIENT) {
            // 患者域在 Prompt 里也要脱敏：模型不需要知道具体是谁，只需要知道"这是本院这个患者的资料"
            sb.append("；患者标识：").append(mask(patientId));
        }
        if (score != null) {
            sb.append(String.format("；相关度 %.2f", score));
        }
        sb.append("）\n");

        String body = content == null ? "" : content.trim();
        if (maxChars > 0 && body.length() > maxChars) {
            body = body.substring(0, maxChars) + "…（内容过长已截断）";
        }
        sb.append(body);
        return sb.toString();
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "已脱敏";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
