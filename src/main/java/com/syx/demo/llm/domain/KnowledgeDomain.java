package com.syx.demo.llm.domain;

/**
 * 知识数据域。这是"回答不泄露其他医院私有数据"这条要求的实现基石。
 *
 * <p>分域的依据是<b>数据归属</b>，不是内容主题：
 * <ul>
 *   <li>{@link #PUBLIC} —— 指南、药品说明书、诊疗规范、操作 SOP。
 *       这些是没有归属的公开医学知识，可以全院共享、可以随产品一起分发；</li>
 *   <li>{@link #PATIENT} —— 患者病历、检验检查、医嘱、既往史。
 *       这些属于"某一家医院的具体患者"，一旦跨机构可见就是医疗数据泄漏事故。</li>
 * </ul>
 *
 * <p>两域在存储层就是<b>物理隔离</b>的：不同的 Redis 索引、不同的 key 前缀
 * （见 {@code VectorStoreConfiguration}）。不靠"查询时加过滤条件"来做安全边界 ——
 * 过滤条件写漏一次就是一个数据泄漏，而物理隔离写漏不了。
 */
public enum KnowledgeDomain {

    /** 院内公共知识域：非患者隐私数据，跨院可共享。 */
    PUBLIC("PUBLIC", "院内公共知识域",
            "指南 / 药品说明书 / 诊疗规范 / 操作 SOP 等非患者隐私数据"),

    /** 患者隐私数据域：仅限本机构患者数据。 */
    PATIENT("PATIENT", "患者隐私数据域",
            "本院患者的病历、检验检查、医嘱、既往史等受保护健康信息");

    /** 写进向量库元数据的取值，检索过滤表达式里用它。 */
    private final String key;
    /** 中文名，用于 prompt 里告诉模型"你现在能看哪一类资料"。 */
    private final String label;
    /** 面向使用者的说明。 */
    private final String description;

    KnowledgeDomain(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /** 供 prompt 使用的数据域描述，例如"院内公共知识域（指南 / 药品说明书 / 诊疗规范）"。 */
    public String describeForPrompt() {
        return label + "（" + description + "）";
    }

    public static KnowledgeDomain fromKey(String key) {
        for (KnowledgeDomain d : values()) {
            if (d.key.equalsIgnoreCase(key)) {
                return d;
            }
        }
        return null;
    }
}
