package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseNature;
import com.family.finance.repository.ExpenseCategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.24 FR-615 · 「这笔算什么性质」的<b>唯一判据点</b>。
 *
 * <h3>为什么必须只有一处</h3>
 *
 * <p>判据本身只有一句话:<b>类目性质 = 一次性,或者这笔被勾了一次性</b>。
 * 写起来一行,所以最容易的做法是在每条 SQL 里写个 {@code CASE WHEN}。
 * 但读它的地方有五处 —— 三分块 / 12 期分层 / 归因瀑布 / 常态月均 / 回流读数 ——
 * 散成五份之后,改判据要改五处,而漏掉的那一处<b>不报错</b>,只是那个块的数字不一样。
 * 这正是这个项目反复踩的那类坑(联动链纪律:口径只能有一个出处)。</p>
 *
 * <p>所以判据收在 {@link #natureOf} 这一个方法里,SQL 只负责把原始列
 * {@code (expense_category_id, one_off)} 取出来。护栏
 * {@code v1240-ONEOFF-SINGLE-JUDGE} 扫「除了这里没有别处判 ONE_OFF」。</p>
 *
 * <h3>三段兜底,一个都不能少</h3>
 *
 * <p>本级 → 父级 → 弹性。第三段是 FR-616:<b>没设过性质的类目一律读作弹性</b>,
 * 因为弹性是三者里最不改变任何现有数字的那一个(常态月均 = 月均支出)。</p>
 *
 * <p>预检 SF3 说的就是这里:二级类目继承父级时,如果父级<b>也</b>是 NULL,
 * 写成「取父级值」再 {@code valueOf} 会 NPE —— 而这个 NPE 发生在报表渲染中途,
 * chunked streaming 下连 /error 页都出不来,用户看到的是白屏。
 * 兜底收在这一处,单测穷举 (null, null) 组合。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseNatureService {

    private final ExpenseCategoryMapper categoryMapper;

    /**
     * 这个家的「类目 id → 性质」解析表,<b>父级继承已经算好</b>。
     *
     * <p>一次读全树(最多 40 行)在内存里解析,不做递归 SQL —— 与 v1.21 的既有做法一致。
     * 调用方拿着这张 map 去喂 {@link #natureOf},不要每笔回查库。</p>
     */
    public Map<Long, ExpenseNature> natureMap(long familyId) {
        List<ExpenseCategory> all = categoryMapper.findByFamily(familyId);
        Map<Long, ExpenseCategory> byId = new LinkedHashMap<>();
        for (ExpenseCategory c : all) byId.put(c.getId(), c);

        Map<Long, ExpenseNature> out = new LinkedHashMap<>();
        for (ExpenseCategory c : all) out.put(c.getId(), resolve(c, byId));
        return out;
    }

    public java.util.Set<Long> expandCategories(long familyId, java.util.Set<Long> selected) {
        var result = new java.util.HashSet<>(selected);
        for (var category : categoryMapper.findByFamily(familyId))
            if (category.getParentId() != null && selected.contains(category.getParentId())) result.add(category.getId());
        return result;
    }

    /**
     * 单个类目的性质:本级 → 父级 → 弹性。
     *
     * <p>树封顶两层,所以「父级的父级」不存在,不需要循环向上找 ——
     * 真出现三层是数据损坏,那时候退化成弹性也是对的(不改变任何数字)。</p>
     */
    private ExpenseNature resolve(ExpenseCategory c, Map<Long, ExpenseCategory> byId) {
        if (c == null) return ExpenseNature.FLEX;
        if (c.getExpenseNature() != null && !c.getExpenseNature().isBlank()) {
            return ExpenseNature.parse(c.getExpenseNature());
        }
        if (c.getParentId() != null) {
            ExpenseCategory parent = byId.get(c.getParentId());
            if (parent != null && parent.getExpenseNature() != null
                    && !parent.getExpenseNature().isBlank()) {
                return ExpenseNature.parse(parent.getExpenseNature());
            }
        }
        return ExpenseNature.FLEX;   // FR-616
    }

    /**
     * <b>FR-615 的那一句话。全站只有这一个实现。</b>
     *
     * @param oneOff      这笔有没有被勾「这笔是一次性的」({@code cash_flow.one_off})
     * @param categoryId  这笔的消费分类;<b>null = 未分类</b>
     * @param natureMap   {@link #natureMap} 的结果
     *
     * <p>未分类的笔算<b>弹性</b>(FR-636)—— 不是丢掉,是如实归进弹性那一段,
     * 并在块下注明有多少笔未分类。把未分类偷偷丢掉会让三分合计小于支出总额,
     * 那是这个项目明令禁止的一类错(L13 硬约束③)。</p>
     */
    public ExpenseNature natureOf(boolean oneOff, Long categoryId,
                                  Map<Long, ExpenseNature> natureMap) {
        if (oneOff) return ExpenseNature.ONE_OFF;                       // 逐笔勾优先(FR-613)
        if (categoryId == null) return ExpenseNature.FLEX;              // 未分类 → 弹性(FR-636)
        ExpenseNature byCat = natureMap.get(categoryId);
        return byCat == null ? ExpenseNature.FLEX : byCat;              // 类目没了 → 弹性(FR-616)
    }

    /** 保存一个类目的性质(FR-612)。{@code null} = 清回继承/弹性。 */
    public void setNature(long familyId, long categoryId, ExpenseNature nature) {
        categoryMapper.setNature(familyId, categoryId, nature == null ? null : nature.name());
    }
}
