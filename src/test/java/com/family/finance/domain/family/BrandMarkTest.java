package com.family.finance.domain.family;

import com.family.finance.repository.FamilyMapper;
import com.family.finance.service.FamilyService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v1.35.1 · 品牌名旁的小图标可以选(维护者 2026-10-20:精选 20–50 个)。
 * 图形只来自这个枚举(模板用 th:utext 放进 svg),所以这里把「只有路径类元素、没有脚本 / 事件属性」钉死。
 */
class BrandMarkTest {

    @Test
    void 数量在维护者要的20到50之间_code唯一且放得进库() {
        BrandMark[] all = BrandMark.values();
        assertThat(all.length).isBetween(20, 50);
        Set<String> codes = Arrays.stream(all).map(BrandMark::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(all.length);
        assertThat(codes).allMatch(c -> c.matches("[a-z0-9-]{1,32}"));   // family.brand_mark VARCHAR(32)
        assertThat(Arrays.stream(all).map(BrandMark::label).collect(Collectors.toSet())).hasSize(all.length);
    }

    @Test
    void 默认是v1_35_0验收过的那只存钱罐() {
        assertThat(BrandMark.DEFAULT).isEqualTo(BrandMark.PIGGY_BANK);
        assertThat(BrandMark.DEFAULT.svg()).startsWith("<path d=\"M19 5c-1.5 0-2.8 1.4-3 2");
        assertThat(BrandMark.DEFAULT.dy()).isEqualTo(-2.0);
        assertThat(BrandMark.DEFAULT.transform()).isEqualTo("translateY(-2px)");
    }

    @Test
    void 认不出的code显示成存钱罐_保存时拒绝() {
        assertThat(BrandMark.of(null)).isEqualTo(BrandMark.PIGGY_BANK);
        assertThat(BrandMark.of("")).isEqualTo(BrandMark.PIGGY_BANK);
        assertThat(BrandMark.of("no-such-icon")).isEqualTo(BrandMark.PIGGY_BANK);
        assertThat(BrandMark.of(" cat ")).isEqualTo(BrandMark.CAT);
        assertThat(BrandMark.byCode("no-such-icon")).isEmpty();
        assertThat(BrandMark.byCode("cat")).contains(BrandMark.CAT);
    }

    @Test
    void 图形里只有路径类元素_没有脚本和事件属性() {
        Pattern tag = Pattern.compile("<([a-zA-Z]+)\\b");
        List<String> allowed = List.of("path", "circle", "rect", "line", "polyline", "polygon", "ellipse");
        for (BrandMark m : BrandMark.values()) {
            Matcher t = tag.matcher(m.svg());
            int n = 0;
            while (t.find()) { assertThat(allowed).as(m.code()).contains(t.group(1)); n++; }
            assertThat(n).as(m.code()).isPositive();
            assertThat(m.svg().toLowerCase()).as(m.code()).doesNotContain("script").doesNotContain(" on").doesNotContain("href");
        }
    }

    @Test
    void 竖直偏移都是量出来的合理范围_格式对() {
        for (BrandMark m : BrandMark.values()) {
            assertThat(m.dy()).as(m.code()).isBetween(-3.0, 0.0);
            assertThat(m.transform()).as(m.code()).matches("translateY\\(-?\\d+(\\.5)?px\\)");
        }
        assertThat(BrandMark.GIFT.transform()).isEqualTo("translateY(-1px)");
        assertThat(BrandMark.CAT.transform()).isEqualTo("translateY(-1.5px)");
    }

    @Test
    void 分组覆盖全部_组内保持声明顺序() {
        List<BrandMark> flat = BrandMark.grouped().values().stream().flatMap(List::stream).toList();
        assertThat(flat).containsExactly(BrandMark.values());
        assertThat(BrandMark.grouped().keySet()).first().isEqualTo("钱财");
    }

    @Test
    void 换图标_选回默认存NULL_不认识的拒绝() {
        FamilyMapper mapper = mock(FamilyMapper.class);
        FamilyService svc = new FamilyService(mapper);
        assertThat(svc.updateBrandMark(1L, "cat")).isEqualTo(BrandMark.CAT);
        verify(mapper).updateBrandMark(1L, "cat");
        svc.updateBrandMark(1L, "piggy-bank");
        verify(mapper).updateBrandMark(1L, null);
        assertThatThrownBy(() -> svc.updateBrandMark(1L, "<script>")).isInstanceOf(IllegalArgumentException.class);
        verify(mapper, times(2)).updateBrandMark(anyLong(), any());
    }
}
