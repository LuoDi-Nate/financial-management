package com.family.finance.domain.group;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v1.20 · 账户组 —— 一个<b>可选的叠加聚合层</b>,含 1..N 个账户。
 *
 * <p><b>它不是账户的属性</b>:account 表一列不加,账户默认不属于任何组。
 * 没建任何组时,所有页面与 v1.19.16 逐字一致 —— 所以这一版<b>不需要开关</b>,
 * 「有没有建组」本身就是开关。</p>
 *
 * <p><b>它也不是记账实体</b>:不能对着组填余额、记收支、导截图。
 * 这一条是刻意的 —— 截图导入天然是「一个 APP 一张图」= 一个账户
 * ({@code HoldingImportService.startOrResume(accountId)}),真把账户合并掉会直接打断它。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountGroup {
    private Long id;
    private Long familyId;
    private String name;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
}
