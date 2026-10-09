package com.family.finance.service.fund;

import com.family.finance.service.penetration.EastMoneyFundClient;
import com.family.finance.service.penetration.EastMoneyFundClient.FundInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.30 · 场外基金的「找得到、分得清」(PRD FR-959 / FR-964 · tech-design/v1.30.md 选型六、七)。
 *
 * <ul>
 *   <li>{@link #search}:6 位代码前缀、名称片段、拼音缩写都能搜到,只在内存里的代码表里匹配(不进 SQL)。</li>
 *   <li>{@link #classify}:这只基金按哪条路记 —— 普通基金按净值、货币基金按金额结转,
 *       外币份额 / 浮动净值货基本版不支持(并给出人话原因)。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FundCatalog {

    private final EastMoneyFundClient client;

    public static final int SEARCH_LIMIT = 20;
    public static final int QUERY_MAX_LEN = 20;

    public enum Kind { NAV, MMF, UNSUPPORTED }

    /** 分类结果:哪条路 + 不支持时的原因 + QDII / FOF 这类晚一个交易日的提示 */
    public record Classified(FundInfo info, Kind kind, String reason, boolean lateNav) {
        public boolean supported() { return kind != Kind.UNSUPPORTED; }
    }

    public List<Classified> search(long familyId, String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.length() > QUERY_MAX_LEN) q = q.substring(0, QUERY_MAX_LEN);
        if (q.isEmpty()) return List.of();
        return rank(client.catalog(familyId), q).stream().map(FundCatalog::classify).toList();
    }

    public Classified find(long familyId, String code) {
        FundInfo f = client.find(familyId, code);
        return f == null ? null : classify(f);
    }

    /** 排序:代码完全相同 > 代码前缀 > 名称开头 > 拼音缩写前缀 > 名称包含 > 拼音全拼前缀;各档内按代码表原顺序 */
    static List<FundInfo> rank(List<FundInfo> all, String q) {
        String up = q.toUpperCase(Locale.ROOT);
        boolean digits = q.chars().allMatch(Character::isDigit);
        List<List<FundInfo>> tiers = new ArrayList<>();
        for (int i = 0; i < 6; i++) tiers.add(new ArrayList<>());
        for (FundInfo f : all) {
            int tier = -1;
            if (digits) {
                if (f.code().equals(q)) tier = 0;
                else if (f.code().startsWith(q)) tier = 1;
            } else {
                String name = f.name() == null ? "" : f.name();
                if (name.startsWith(q)) tier = 2;
                else if (f.abbr() != null && f.abbr().startsWith(up)) tier = 3;
                else if (name.contains(q)) tier = 4;
                else if (f.pinyin() != null && f.pinyin().startsWith(up)) tier = 5;
            }
            if (tier >= 0) tiers.get(tier).add(f);
        }
        List<FundInfo> out = new ArrayList<>();
        for (List<FundInfo> t : tiers) {
            for (FundInfo f : t) {
                if (out.size() >= SEARCH_LIMIT) return out;
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 按代码表的类型与名称分类(§零 实测:接口里货币基金的「单位净值」那一格其实是每万份收益,
     * 浮动净值货基又是真净值、美元份额没有币种字段 —— 只能靠代码表的类型与名称分)。
     */
    public static Classified classify(FundInfo f) {
        String type = f.type() == null ? "" : f.type();
        String name = f.name() == null ? "" : f.name();
        if (type.startsWith("货币型-浮动净值")) {
            return new Classified(f, Kind.UNSUPPORTED, "浮动净值型货币基金暂不支持,可以先按手填市值记", false);
        }
        if (name.contains("美元") || name.contains("港币") || name.contains("港元")) {
            return new Classified(f, Kind.UNSUPPORTED, "暂不支持美元 / 港币份额,可以先按手填市值记", false);
        }
        if (type.startsWith("货币型")) return new Classified(f, Kind.MMF, null, false);
        boolean late = type.startsWith("QDII") || type.contains("海外") || name.contains("QDII")
                || type.startsWith("FOF");
        return new Classified(f, Kind.NAV, null, late);
    }
}
