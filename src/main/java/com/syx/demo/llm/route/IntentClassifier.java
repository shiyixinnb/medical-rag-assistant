package com.syx.demo.llm.route;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别器 —— 用规则而不是再用一次模型调用。
 *
 * <p>为什么不用小模型做意图分类？因为这是一个"必须零延迟、零成本、可解释、可回归测试"的判定：
 * 每次急诊问答前多一次 LLM 调用，既拖慢首字延迟又要多花钱，而且模型分类的结果不稳定 ——
 * 同一句话今天分到"用药咨询"、明天分到"通用问答"，路由行为就不可复现了。
 * 医疗场景里"为什么这个问题走了便宜模型"必须是可解释、可写进验收用例的。
 *
 * <p>所以这里采用<b>加权关键字打分 + 上下文继承</b>：命中哪些词、为什么选这个意图，
 * 全部记录在 {@link IntentSignal} 里随响应一起返回。
 *
 * <p><b>【阅读导航】</b>这个类只有一个公开方法 {@link #classify}，它内部是五步：
 * <pre>
 *   1. 加权打分     遍历 KEYWORDS，命中一个词就加该词的权重
 *   2. 长度补偿     长问题额外给 COMPLEX_REASONING 加 1 分（长描述通常意味着多因素权衡）
 *   3. 取最高分     同分时按枚举声明顺序（= 业务优先级）决胜
 *   4. 追问继承     一个词都没命中，但看起来像追问 → 继承上一轮意图
 *   5. 兜底         极短输入按闲聊处理，否则走 GENERAL_QA
 * </pre>
 *
 * <p>读的时候注意一件事：<b>权重不是拍脑袋写的</b>。
 * {@code PATIENT_RECORD_QUERY} 给到 3.0（其他多为 1.0~2.0），因为它决定"要不要放开患者数据域"——
 * 这类判断宁可多问一句，也不能因为漏判而越权。这就是"业务权重 > 语义准确率"的取舍。
 */
@Component
public class IntentClassifier {

    /** 关键字字典。权重越高代表这个词越"专有"，越不容易误伤。 */
    private static final Map<QuestionIntent, Map<String, Double>> KEYWORDS = new LinkedHashMap<>();

    static {
        // ---- 患者病历：权重给得很高，因为它决定数据域是否放开，宁可多问一句也不能越权
        put(QuestionIntent.PATIENT_RECORD_QUERY, 3.0,
                "我的", "患者", "病人", "该患者", "这位患者", "病历", "病史", "既往史", "主诉",
                "现病史", "过敏史", "家族史", "检验", "化验", "检查结果", "报告单", "影像",
                "心电图", "血常规", "生化", "医嘱", "入院记录", "出院小结", "床号", "住院号",
                "门诊号", "住院病人", "这个患者", "本人");

        // ---- 急诊分诊
        put(QuestionIntent.EMERGENCY_TRIAGE, 2.0,
                "急救", "抢救", "心肺复苏", "cpr", "休克", "昏迷", "意识", "呼吸骤停", "心跳骤停",
                "胸痛", "大出血", "止血", "窒息", "抽搐", "惊厥", "高热", "中暑", "溺水",
                "触电", "中毒", "分诊", "生命体征", "血压下降", "血氧", "危重", "濒危",
                "呼吸困难", "紫绀", "瞳孔", "气道", "除颤", "转运");

        // ---- 复杂推理
        put(QuestionIntent.COMPLEX_REASONING, 1.5,
                "为什么", "机制", "原理", "鉴别", "鉴别诊断", "如何鉴别", "多因素", "综合判断",
                "权衡", "联合用药", "冲突", "矛盾", "评估", "预后", "病理生理", "因果",
                "分析", "推理", "区别", "优于", "首选还是", "该不该", "怎么判断", "相互影响");

        // ---- 用药咨询
        put(QuestionIntent.DRUG_QUERY, 1.2,
                "用药", "剂量", "用法", "用量", "禁忌", "相互作用", "配伍", "不良反应", "说明书",
                "抗生素", "头孢", "阿莫西林", "肾上腺素", "多巴胺", "副作用", "停药",
                "口服", "滴速", "输注", "静脉", "肌注", "半衰期", "毫克", "mg");

        // ---- 指南规范
        put(QuestionIntent.GUIDELINE_QUERY, 1.2,
                "指南", "规范", "共识", "标准", "流程", "路径", "分级", "循证", "诊疗方案",
                "操作规范", "sop", "制度", "首诊负责", "会诊");

        // ---- 闲聊
        put(QuestionIntent.CHITCHAT, 1.0,
                "你好", "您好", "hi", "hello", "谢谢", "再见", "在吗", "你是谁", "天气",
                "哈哈", "笑话", "能干什么", "你会什么");
    }

    /** 指代性词：出现这些词说明是追问，应该继承上一轮意图。 */
    private static final List<String> ANAPHORA = List.of(
            "那", "这个", "它", "还有", "继续", "然后呢", "为什么", "怎么办", "再来一个",
            "详细说", "展开", "上面", "刚才", "前面", "呢？", "呢?");

    private static void put(QuestionIntent intent, double weight, String... words) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (String w : words) {
            map.put(w, weight);
        }
        KEYWORDS.put(intent, map);
    }

    /**
     * 分类。
     *
     * @param question      本轮问题
     * @param historyIntents 近期意图（最近的在前），用于追问继承与意图切换判定
     * @param reasoningLengthThreshold 超过该长度视为推理密集
     */
    public IntentSignal classify(String question, List<QuestionIntent> historyIntents, int reasoningLengthThreshold) {
        String q = question == null ? "" : question.trim();
        String lower = q.toLowerCase();

        // 1. 加权打分
        Map<QuestionIntent, Double> scores = new LinkedHashMap<>();
        Map<QuestionIntent, List<String>> hitWords = new LinkedHashMap<>();
        for (Map.Entry<QuestionIntent, Map<String, Double>> entry : KEYWORDS.entrySet()) {
            double score = 0;
            List<String> hits = new ArrayList<>();
            for (Map.Entry<String, Double> kw : entry.getValue().entrySet()) {
                if (lower.contains(kw.getKey())) {
                    score += kw.getValue();
                    hits.add(kw.getKey());
                }
            }
            if (score > 0) {
                scores.put(entry.getKey(), score);
                hitWords.put(entry.getKey(), hits);
            }
        }

        // 2. 长问题额外加推理权重：长描述通常意味着多因素权衡
        if (q.length() >= Math.max(20, reasoningLengthThreshold)) {
            scores.merge(QuestionIntent.COMPLEX_REASONING, 1.0, Double::sum);
            hitWords.computeIfAbsent(QuestionIntent.COMPLEX_REASONING, k -> new ArrayList<>())
                    .add("问题长度≥" + Math.max(20, reasoningLengthThreshold) + "字");
        }

        // 3. 选最高分。同分时按枚举声明顺序（= 业务优先级）决胜
        QuestionIntent best = null;
        double bestScore = 0;
        for (Map.Entry<QuestionIntent, Double> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                best = e.getKey();
            }
        }

        if (best != null) {
            List<String> hits = hitWords.getOrDefault(best, List.of());
            double confidence = Math.min(1.0, bestScore / 4.0);
            return IntentSignal.of(best, hits, "关键字加权得分 " + bestScore, confidence);
        }

        // 4. 没有命中任何关键字：可能是追问，尝试继承上一轮意图
        QuestionIntent previous = firstOrNull(historyIntents);
        if (previous != null && looksLikeFollowUp(q)) {
            return new IntentSignal(previous, List.of(), "未命中关键字且疑似追问，继承上一轮意图", 0.3, true);
        }

        // 5. 兜底
        if (previous != null && q.length() <= 12) {
            return new IntentSignal(QuestionIntent.CHITCHAT, List.of(), "极短输入且无医学要素，按闲聊处理", 0.2, false);
        }
        return IntentSignal.of(QuestionIntent.GENERAL_QA, List.of(), "无关键字命中，按通用问答处理", 0.2);
    }

    private boolean looksLikeFollowUp(String q) {
        if (q.isEmpty()) {
            return false;
        }
        if (q.length() <= 15) {
            return true;
        }
        for (String a : ANAPHORA) {
            if (q.contains(a)) {
                return true;
            }
        }
        return false;
    }

    private QuestionIntent firstOrNull(List<QuestionIntent> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }
}
