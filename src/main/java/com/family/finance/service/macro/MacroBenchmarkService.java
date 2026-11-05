package com.family.finance.service.macro;

import com.family.finance.calc.BenchmarkAverage;
import com.family.finance.domain.macro.MacroBenchmark;
import com.family.finance.repository.MacroBenchmarkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * v0.5 FR-70/71 · 宏观基准读取 + 三法均值推导。
 *
 * <p>数据来源:V27 seed(1990-2025)+ 管理页手动校正 + 年度 cron(MacroFetchJob)。
 * 推导(BenchmarkAverage):全历史几何 / 剔极端值几何(默认)/ 近 10 年几何。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MacroBenchmarkService {

    /** 剔极端值比例(头尾各 10%)· 默认对比线 */
    public static final double TRIM_FRACTION = 0.10;
    /** 近期窗口年数 */
    public static final int RECENT_YEARS = 10;

    private final MacroBenchmarkMapper mapper;

    public List<MacroBenchmark> all() {
        return mapper.findAll();
    }

    public MacroBenchmark latest() {
        List<MacroBenchmark> all = mapper.findAll();
        return all.isEmpty() ? null : all.getLast();
    }

    public void upsert(MacroBenchmark b) {
        if("manual".equals(b.getSource())) b.setVerifiedAt(java.time.LocalDateTime.now());
        mapper.upsert(b);
        log.info("macro_benchmark upsert · year={} cpi={} m2={} source={}",
                b.getYear(), b.getCpiHeadline(), b.getM2Growth(), b.getSource());
    }

    /** CPI 三法均值(全历史几何 / 剔极端值几何 默认 / 近10年几何)。 */
    public Averages cpiAverages() {
        List<BigDecimal> series = all().stream().map(MacroBenchmark::getCpiHeadline).toList();
        return averagesOf(series);
    }

    /** M2 三法均值。 */
    public Averages m2Averages() {
        List<BigDecimal> series = all().stream().map(MacroBenchmark::getM2Growth).toList();
        return averagesOf(series);
    }

    private Averages averagesOf(List<BigDecimal> chronological) {
        return new Averages(
                BenchmarkAverage.geometricMean(chronological),
                BenchmarkAverage.trimmedGeometricMean(chronological, TRIM_FRACTION),
                BenchmarkAverage.recentAverage(chronological, RECENT_YEARS));
    }

    /**
     * 三法均值结果。
     * @param fullGeometric  全历史几何均值
     * @param trimmedGeometric 剔极端值几何均值(默认对比线)
     * @param recent         近 10 年几何均值
     */
    public record Averages(BigDecimal fullGeometric, BigDecimal trimmedGeometric, BigDecimal recent) {
        /** 默认对比值 = 剔极端值几何均值。 */
        public BigDecimal defaultValue() { return trimmedGeometric; }
    }
}
