package com.family.finance.domain.ledger;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.30 · 补录本金(PRD FR-973 ~ 975 · tech-design/v1.30.md 选型十二)。
 *
 * <p>一笔「以前就有、这期才补录进来」的钱。口径上和「开账基线」是同一件事 —— 新账户第一期的余额整笔算开账基线,
 * 这里是把同一个判断开放给<b>已有历史</b>的账户:这一期余额里有 {@link #amount} 是本来就有的,
 * 不算收入,也不算投资收益。</p>
 *
 * <p>进指标的唯一入口是事实层(FactMapper → FactProjector → FactViewServiceImpl.openingBaseline),
 * 别处不许自己再读这张表算钱(护栏 {@code v130-PRINCIPAL-ONE-PATH})。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrincipalAdjustment {
    private Long id;
    private Long familyId;
    private Long accountId;
    private Long periodId;
    /** 账户币种,> 0 */
    private BigDecimal amount;
    /** 添加基金 / 改份额时顺带记的,指向那只持仓;账户详情里手记的为 null */
    private Long holdingId;
    private String note;
    /** v1.18 流水来源(只有人会记它 → MANUAL) */
    private String sourceTag;
    private Long memberId;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    /** 查询时带出(时间线显示用) */
    private String holdingName;
}
