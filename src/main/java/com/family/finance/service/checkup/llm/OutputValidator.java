package com.family.finance.service.checkup.llm;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM 综合诊断输出校验 · v0.2 FR-40c · 2026-05-14 修订(v0.4.7 放宽)
 *
 * <p><b>v0.4.7 放宽</b>(基于 prod 调仓建议反馈 · 误杀率 > 真泄露率):
 * <ul>
 *   <li>删除「古典中式词」扫描(品牌口吻偏好 · 用户感知低 · 误杀风险高)</li>
 *   <li>删除「过度客套」(您 > 2 次)扫描(同上)</li>
 *   <li>真名扫描门槛从 length ≥ 2 → length ≥ 3(避免「萝卜/张三/李四」2 字常用组合误杀)</li>
 * </ul>
 *
 * <p><b>当前校验策略</b>(保留的都是真正有意义的):
 * <ol>
 *   <li>长度限制 150-700 字符</li>
 *   <li>担保性话术拒绝(保证 / 稳赚 / 零风险 ...)— <b>合规底线 · 不可放</b></li>
 *   <li>具体产品名 / 股票代码拒绝(余额宝 / 茅台 / 510300...)+ 账户名白名单</li>
 *   <li>真名泄露扫描(防御深度;只对 length ≥ 3 的真名)— rebalance 等 prompt 端不传真名的
 *       caller 应传空 realNames 集合跳过</li>
 *   <li>至少含一个金融术语(防 LLM 跑题成心灵鸡汤)</li>
 * </ol>
 *
 * <p>任意一项失败 → reject。
 */
public final class OutputValidator {

    /** 长度下限:综合诊断需要叙事完整,150 字以下信号太弱 */
    private static final int MIN_LEN = 150;
    /** 长度上限:v0.4.7 起放宽到 1500(原 700 卡 rebalance JSON · narrative + 4 actions 各带 reason
     *  常见 800-1000 字 · 1500 字仍能挡住明显废话连篇 · 给 LLM 详细说理空间) */
    private static final int MAX_LEN = 1500;

    /** 真名扫描最小长度:&lt; 3 字的真名(如「萝卜」「张三」)在自然语言中常用,误杀率高 */
    private static final int MIN_REAL_NAME_LEN = 3;

    /** 担保性话术(最不能容忍 — 涉及金融建议合规) */
    private static final List<String> GUARANTEE_PHRASES = List.of(
            "保证", "稳赚", "一定能", "必然", "零风险", "包赚", "无风险套利", "稳定盈利",
            "肯定盈利", "绝对收益", "保底", "保收益"
    );

    // 仅识别完整的月报风险提示。不能凭「保证」前有否定词放行整句。
    private static final Pattern GOAL_REPORT_RISK_NOTE = Pattern.compile(
            "(?m)(^|[。！？；])\\h*(?:(?:历史收益(?:和物价变化)?|历史回报|历史表现|"
            + "本(?:报告|测算|结果)|(?:以上|上述)测算)(?:仅供参考[，,]\\h*)?)?"
            + "(?:不|并不|不能|无法)保证(?:未来(?:收益|回报|结果|表现)|"
            + "(?:按期|如期)(?:退休|达成目标)|退休安全)"
            + "\\h*(?=$|[。！？；])");

    /**
     * v0.6 · 资产洞察额外禁词:预测涨跌 / 择时 / 买卖时点 —— 洞察是中立诊断,不做行情预测与择时。
     * 仅在 {@link #checkInsight} 路径生效,不影响既有体检综合诊断({@link #check})。
     */
    private static final List<String> PREDICTION_PHRASES = List.of(
            "会涨", "会跌", "将涨", "将跌", "看涨", "看跌", "见顶", "见底", "抄底", "逃顶",
            "牛市", "熊市", "暴涨", "暴跌", "大涨", "大跌", "反弹在即", "即将上涨", "即将下跌",
            "未来上涨", "未来下跌", "目标价", "现在买入", "现在卖出", "马上加仓", "立即减仓",
            "择时", "波段操作", "高抛低吸"
    );

    /** 具体产品名 / 股票代码 — 这是合规底线
     *  v0.4.9:6 位数字判断收紧 · 前面不能是 ¥/$/HK$/数字/小数点 · 后面不能是元/万/千/亿/年/月/日/天/.
     *  否则 ¥120526(金额)/ 2026 年(日期)被当 A 股代码误杀 */
    private static final Pattern PRODUCT_NAME_PATTERN = Pattern.compile(
            "余额宝|余利宝|微信零钱通|招商银行某|工商银行某|平安某|建设银行某|农业银行某|"
            + "茅台|宁德时代|腾讯|阿里|美团|京东|"
            + "510\\d{3}|512\\d{3}|513\\d{3}|159\\d{3}|" // ETF 代码常见前缀
            + "(?<![¥$￥0-9.])\\b\\d{6}\\b(?![元万千亿年月日天.])" // 6 位数字 A 股代码 · 排除金额/日期
    );

    private OutputValidator() {}

    /**
     * 校验综合诊断输出(无账户白名单 · 老 caller 兼容)。
     */
    public static Result check(String polished, Set<String> realNames) {
        return check(polished, realNames, java.util.Set.of());
    }

    /**
     * 校验综合诊断输出。
     *
     * @param polished           LLM 输出文本
     * @param realNames          原始真名列表(防御深度:扫描 LLM 输出是否泄露真名)
     * @param accountWhitelist   用户已有账户名集合(子串匹配)· 调仓建议场景 · LLM 引用用户自己账户合法
     *                           (e.g. 用户有「支付宝-余额宝」账户 · LLM 说"从余额宝调出" 不算产品推荐)
     */
    public static Result check(String polished, Set<String> realNames, Set<String> accountWhitelist) {
        return check(polished, realNames, accountWhitelist, false);
    }

    public static Result checkGoalMonthlyReport(String polished, Set<String> realNames) {
        return check(polished, realNames, Set.of(), true);
    }

    private static Result check(String polished, Set<String> realNames, Set<String> accountWhitelist,
                                boolean goalMonthlyReport) {
        if (polished == null || polished.isBlank()) {
            return Result.reject("空字符串");
        }
        String trimmed = polished.trim();
        int len = trimmed.length();
        if (len < MIN_LEN) {
            return Result.reject("文本过短 len=" + len + "(< " + MIN_LEN + ")");
        }
        if (len > MAX_LEN) {
            return Result.reject("文本过长 len=" + len + "(> " + MAX_LEN + ")");
        }

        // 1. 担保性话术(最严重 · 合规底线)
        String guaranteeText = goalMonthlyReport
                ? GOAL_REPORT_RISK_NOTE.matcher(trimmed).replaceAll("$1") : trimmed;
        for (String w : GUARANTEE_PHRASES) {
            if (guaranteeText.contains(w)) {
                return Result.reject("含担保性话术: \"" + w + "\"");
            }
        }

        // 2. 具体产品名 / 股票代码
        //    白名单:如果匹配命中的产品名是「用户已有账户名」的子串,放行
        //    (调仓建议场景 · 用户对自己账户有完全知情权 · LLM 引用自己账户不算产品推荐)
        var matcher = PRODUCT_NAME_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            String hit = matcher.group();
            boolean isUserAccount = accountWhitelist != null && accountWhitelist.stream()
                .anyMatch(name -> name != null && name.contains(hit));
            if (!isUserAccount) {
                return Result.reject("含具体产品名/代码: \"" + hit + "\"");
            }
        }

        // 3. 真名泄露扫描(防御深度 · length ≥ 3 防止 2 字常用组合「萝卜/张三」误杀)
        //    caller 在 prompt 端不传真名时(如 rebalance)应传空 realNames 集合完全跳过
        if (realNames != null && !realNames.isEmpty()) {
            for (String name : realNames) {
                if (name != null && name.length() >= MIN_REAL_NAME_LEN && trimmed.contains(name)) {
                    return Result.reject("真名泄露: \"" + name + "\"");
                }
            }
        }

        // 4. 至少包含一个金融术语(避免 LLM 跑题成"心灵鸡汤")
        //    v0.16.1 · 补目标/金额类词汇:自定义追踪目标(账户金额/现金/储蓄达标)的叙事
        //    此前一个原有术语都不含 → 被误判「跑题」反复无法生成(防跑题深度校验,非合规红线,可安全放宽)
        boolean hasFinanceTerm = trimmed.contains("年化") || trimmed.contains("配置")
                || trimmed.contains("资产") || trimmed.contains("流动")
                || trimmed.contains("风险") || trimmed.contains("收益")
                || trimmed.contains("基准") || trimmed.contains("应急")
                || trimmed.contains("负债") || trimmed.contains("偿还")
                || trimmed.contains("目标") || trimmed.contains("达标") || trimmed.contains("达成")
                || trimmed.contains("储蓄") || trimmed.contains("存款") || trimmed.contains("现金")
                || trimmed.contains("余额") || trimmed.contains("账户") || trimmed.contains("本金")
                || trimmed.contains("金额") || trimmed.contains("净值") || trimmed.contains("进度");
        if (!hasFinanceTerm) {
            return Result.reject("无金融术语,可能跑题");
        }

        return Result.accept();
    }

    /**
     * v0.6 · 资产洞察专用校验:在 {@link #check} 全部规则之上,额外拒绝「预测涨跌 / 择时 / 买卖时点」。
     * 洞察是中立诊断({@code 不预测涨跌 · 不择时 · 不荐产品} 红线),比体检综合诊断更严。
     *
     * @param polished  LLM 输出文本
     * @param realNames 真名集合(洞察 prompt 不含名 · 通常传空 · 防御深度仍扫)
     */
    public static Result checkInsight(String polished, Set<String> realNames) {
        Result base = check(polished, realNames);
        if (!base.accepted()) return base;
        String trimmed = polished.trim();
        for (String w : PREDICTION_PHRASES) {
            if (trimmed.contains(w)) {
                return Result.reject("含预测/择时话术: \"" + w + "\"(洞察须中立 · 不预测涨跌不择时)");
            }
        }
        return Result.accept();
    }

    public record Result(boolean accepted, String reason) {
        public static Result accept() { return new Result(true, null); }
        public static Result reject(String reason) { return new Result(false, reason); }
    }
}
