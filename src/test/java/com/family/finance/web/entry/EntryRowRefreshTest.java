package com.family.finance.web.entry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-13 删除账户真实用例顺带抓到的老问题:
 * 账户详情页 ✕ 删流水是整页表单,原来拿到的是填报页那一行的 HTML 片段 —— 现在回到点 ✕ 的那个账户详情页,
 * 只认本站 /accounts/{数字},别的 Referer 一律回到这一笔所在的账户(不做开放跳转)。
 */
class EntryRowRefreshTest {

    @Test
    void fullPageDelete_goesBackToTheAccountPageItCameFrom() {
        assertThat(EntryController.backToAccountPage("http://127.0.0.1:20000/accounts/14", 2)).isEqualTo("redirect:/accounts/14");
        assertThat(EntryController.backToAccountPage("https://example.com/accounts/14", 2)).as("别的站的同名路径也只取路径").isEqualTo("redirect:/accounts/14");
    }

    @Test
    void anythingElse_fallsBackToTheEntrysOwnAccount() {
        assertThat(EntryController.backToAccountPage(null, 2)).isEqualTo("redirect:/accounts/2");
        assertThat(EntryController.backToAccountPage("http://127.0.0.1:20000/entry", 2)).isEqualTo("redirect:/accounts/2");
        assertThat(EntryController.backToAccountPage("http://127.0.0.1:20000/accounts/14/edit", 2)).isEqualTo("redirect:/accounts/2");
        assertThat(EntryController.backToAccountPage("::not a uri::", 2)).isEqualTo("redirect:/accounts/2");
    }
}
