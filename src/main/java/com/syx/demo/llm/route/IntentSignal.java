package com.syx.demo.llm.route;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图分类结果。
 *
 * <p>故意保留 {@code hits}（命中的关键字）和 {@code reason}（判定理由）：线上出问题时，
 * "为什么这个问题走了便宜模型 / 为什么放开了病历域"必须能一眼看到答案，
 * 否则路由逻辑就是个黑盒，没人敢动。
 */
public final class IntentSignal {

    private final QuestionIntent intent;
    private final List<String> hits;
    private final String reason;
    /** 判定置信度：关键字命中的强度，0~1。 */
    private final double confidence;
    /** 是否由上一轮意图继承而来（追问场景）。 */
    private final boolean inherited;

    public IntentSignal(QuestionIntent intent, List<String> hits, String reason,
                        double confidence, boolean inherited) {
        this.intent = intent;
        this.hits = hits == null ? new ArrayList<>() : hits;
        this.reason = reason;
        this.confidence = confidence;
        this.inherited = inherited;
    }

    public static IntentSignal of(QuestionIntent intent, List<String> hits, String reason, double confidence) {
        return new IntentSignal(intent, hits, reason, confidence, false);
    }

    public QuestionIntent getIntent() {
        return intent;
    }

    public List<String> getHits() {
        return hits;
    }

    public String getReason() {
        return reason;
    }

    public double getConfidence() {
        return confidence;
    }

    public boolean isInherited() {
        return inherited;
    }

    @Override
    public String toString() {
        return intent + "(" + reason + (inherited ? ", 继承上一轮" : "") + ")";
    }
}
