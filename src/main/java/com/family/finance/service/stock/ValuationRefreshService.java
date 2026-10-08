package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.family.finance.service.fund.FundNavService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * v1.30 · 两个刷新按钮(持仓页「刷新持仓估值」、填报页「刷新估值」)的<b>唯一入口</b>(tech-design/v1.30.md 选型五)。
 *
 * <p>以前两处各写了一份 {@code List.of(US, CN, HK, CRYPTO, METAL)} 循环;v0.14 就因为分母写死出过「4/3」。
 * 这次要加基金、还要点名失败的基金(FR-963),再复制一遍就是第三份 —— 收口到这里,两个按钮只调它
 * (护栏 {@code v130-ONE-REFRESH-ENTRY})。顺序:股票各市场拉价 → 基金净值 / 货币基金结转 → 估值写回。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValuationRefreshService {

    /** 市场清单的单一来源 */
    public static final List<Market> MARKETS = List.of(Market.US, Market.CN, Market.HK, Market.CRYPTO, Market.METAL);

    private final StockPriceScheduler scheduler;
    private final FundNavService fundNavService;
    private final AccountValuationService valuationService;

    public record RefreshReport(int marketsOk, int marketsTotal, FundNavService.Report funds, int accountsRefreshed) {
        public boolean allMarketsOk() { return marketsOk == marketsTotal; }
    }

    public RefreshReport refreshFamily(long familyId, Long memberId) {
        int ok = 0;
        for (Market mk : MARKETS) {
            try {
                scheduler.fetchMarket(mk);
                ok++;
            } catch (Exception e) {
                log.warn("refresh · fetchMarket failed · market={}: {}", mk, e.toString());
            }
        }
        FundNavService.Report funds;
        try {
            funds = fundNavService.refresh(familyId, FundNavService.BUTTON_THROTTLE);
        } catch (Exception e) {
            log.warn("refresh · 基金净值同步失败: {}", e.toString());
            funds = new FundNavService.Report(0, 0, List.of("基金净值同步出错"), 0);
        }
        int accounts = 0;
        try {
            accounts = valuationService.refreshAllForFamily(familyId, AccountValuationService.TriggerKind.MANUAL, memberId);
        } catch (Exception e) {
            log.warn("refresh · valuation refresh failed: {}", e.toString());
        }
        return new RefreshReport(ok, MARKETS.size(), funds, accounts);
    }

    /** 给 toast / 持仓页提示用的一句话(FR-963:点名没拉到的基金) */
    public static String summary(RefreshReport r) {
        StringBuilder sb = new StringBuilder();
        if (r.allMarketsOk()) sb.append(r.marketsTotal()).append(" 市场估值已刷新");
        else if (r.marketsOk() > 0) sb.append("仅 ").append(r.marketsOk()).append("/").append(r.marketsTotal()).append(" 市场估值刷新成功");
        else sb.append(r.marketsTotal()).append(" 市场估值均刷新失败 · 上游限流/网络");
        if (r.funds() != null && r.funds().any()) {
            sb.append(" · ").append(r.funds().navRows()).append(" 只基金");
            if (r.funds().mmfAccrued() > 0) sb.append("(货币基金结转 ").append(r.funds().mmfAccrued()).append(" 只)");
        }
        sb.append(" · ").append(r.accountsRefreshed()).append(" 账户");
        if (r.funds() != null && !r.funds().failed().isEmpty()) {
            sb.append(" · 没拉到:").append(String.join(";", r.funds().failed()));
        }
        return sb.toString();
    }

    public static boolean clean(RefreshReport r) {
        return r.allMarketsOk() && (r.funds() == null || r.funds().failed().isEmpty());
    }
}
