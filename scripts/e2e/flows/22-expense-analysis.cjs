/**
 * flow · v1.24 支出分析(归因瀑布 / 三分 / 常态月均 / 两块合并)
 *
 * 全程**从页面点击发起**:在类目页改性质、在填报页看勾、在管理页拨开关 ——
 * 不调任何端点。
 *
 * 它能抓到 curl 版抓不到的东西:
 *   · 合并之后页面上到底还有几个「钱花在哪了」标题(curl 数得出字符串,
 *     但数不出它们是不是同一个 section、目录锚点指没指对)
 *   · 三分条的三段是不是真的画出来了、并列元素尺寸齐不齐
 *   · 性质下拉 onchange 自动提交这件事点不点得动
 *   · 关掉开关之后,**别处**的「常态月均」是不是真的一起消失了 ——
 *     半开状态(报表关了、体检还在冒)靠一页一页 curl 很容易漏
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const SEED22 = 'e2e-22 · 跨月前置';

module.exports = {
  name: '22-expense-analysis',
  title: 'v1.24 · 支出分析:三分 / 归因 / 常态月均 / 两块合并',

  async run(ui, report) {
    ui.flow = this.name;

    // ── 1 · 两个 section 真的合并了(FR-661/662)────────────────────────
    report.section('1 · 支出章节合并(FR-661/662)');

    await ui.goto('/reports');
    await ui.rendered('报表页');
    // 【一个标题】—— 合并前这里有两个都叫「钱花在哪了」的 section,
    //   而它们的数本来就不相等,用户第一反应是程序算错了
    await ui.count('h2:text-is("钱花在哪了")', 1, '只有一个「钱花在哪了」标题');
    await ui.count('#sec-expense-mix', 1, '只有一个支出 section');
    await ui.notVisible('#sec-expense-split', '旧的 sec-expense-split 已经不存在');
    await ui.seesText('第一层 · 按资金性质', '第一层标题在(合计 = 家庭支出总额)');
    await ui.seesText('其中 · 日常开支', '第二层标题写明它展开的是哪一片');

    // 目录也必须跟着合并(联动链 L4)—— 漏了的话目录里挂着一个不存在的锚点
    // 【2 不是重复】—— 目录渲染两份:桌面常驻 rail + 手机抽屉 sheet,
    //   共用同一份 tocItems。要守的是「同一份里只有一条支出条目」,
    //   合并前是 4 个(两份 × 两条)。
    await ui.count('a[href="#sec-expense-mix"]', 2, '目录里支出只剩一条(rail + 手机抽屉各一份)');
    await ui.notSeesText('支出构成 · 分类', '目录里旧的第二条已经没了');

    // ── 2 · 三块新内容画出来了(FR-620/630/632)─────────────────────────
    report.section('2 · 归因瀑布 / 三分 / 常态月均');

    await ui.seesText('为什么变了', '归因瀑布块在');
    await ui.seesText('这些钱砍得掉吗', '三分块在');
    await ui.seesText('刚性占比', 'FR-634 刚性占比单独成指标');
    await ui.seesText('常态月均', 'FR-632 常态月均与月均支出并排');
    await ui.seesText('金额差是主信息', '瀑布图例在(给下面的反向断言一个真的参照)');
    // 【不给好坏评价】—— 花钱没有对错,给它一个红灯就是替用户的人生下判断
    await ui.notSeesText('刚性偏高', '不给好坏评价');
    await ui.notSeesText('建议削减', '不给好坏评价');

    const splitRows = db.num(
      `SELECT COUNT(*) FROM cash_flow cf
         JOIN account a ON a.id = cf.account_id
        WHERE a.family_id=${fx.FAM} AND a.archived_at IS NULL
          AND cf.kind='EXPENSE' AND cf.category_code='consumption'
          AND cf.deleted_at IS NULL AND cf.is_adjustment=0`);
    await ui.assert(splitRows > 0, '真值层:确实有消费笔可以三分',
                    `可三分的消费笔数=${splitRows}`);

    // ── 3 · 类目性质改得动,而且立即对全历史生效(FR-612)───────────────
    report.section('3 · 类目性质(FR-610/612)');

    await ui.goto('/expense/categories');
    await ui.rendered('类目管理页');
    // 【页面上是两个问句,不是三个词】—— 非技术家人要能答得上来
    await ui.seesText('每月都有 · 过不下去', '刚性写成问句的答案,不是「刚性」两个字');
    await ui.seesText('不会再来 · 这次才有', '一次性同理');
    await ui.notSeesText('RIGID', '面向用户的地方不露技术枚举值');

    const catId = db.num(
      `SELECT id FROM expense_category WHERE family_id=${fx.FAM} AND parent_id IS NULL
        AND name='餐饮美食' LIMIT 1`);
    if (catId) {
      const before = db.one(`SELECT expense_nature FROM expense_category WHERE id=${catId}`);
      const cfBefore = db.num(
        `SELECT COUNT(*) FROM cash_flow cf JOIN period p ON p.id=cf.period_id
          WHERE p.family_id=${fx.FAM} AND cf.expense_category_id=${catId}`);

      /* 这个下拉是 onchange="this.form.submit()" —— 选完【立刻导航】。
         不等导航落定就断言,page.evaluate 会撞上被销毁的执行上下文
         (报「Execution context was destroyed」,看着像断言失败,其实是时序)。 */
      await Promise.all([
        ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 20000 })
          .catch(() => {}),
        ui.selectByName('nature', 'RIGID',
          `form[action*="/expense/categories/${catId}/nature"]`),
      ]);
      await ui.seesText('立即对全部历史生效', '回执说清了它是立刻生效的');

      const after = db.one(`SELECT expense_nature FROM expense_category WHERE id=${catId}`);
      await ui.assert(after === 'RIGID', '真值层:库里性质确实改了',
                      `改前=${before} 改后=${after}`);

      // 【一行 cash_flow 都不该被回写】—— 性质是类目的属性,不是笔的属性;
      //   落到笔上的话改一次性质就要批量 UPDATE 几百行,那不是「生效」是改写历史
      const cfAfter = db.num(
        `SELECT COUNT(*) FROM cash_flow cf JOIN period p ON p.id=cf.period_id
          WHERE p.family_id=${fx.FAM} AND cf.expense_category_id=${catId}`);
      await ui.assert(cfBefore === cfAfter, '改性质不回写任何一行流水',
                      `改前 ${cfBefore} 行 · 改后 ${cfAfter} 行`);
    } else {
      report.skip(this.name, '类目性质', 'beta 上没有「餐饮美食」这个起步包类目');
    }

    // ── 4 · 一次性勾在两条写入路上都有(FR-613)─────────────────────────
    report.section('4 · 「这笔是一次性的」(FR-613)');

    await ui.goto('/entry');
    await ui.rendered('填报页');
    await ui.visible('input[name="oneOff"]', '填报页有「这笔是一次性的」勾');
    // 默认不勾 —— 绝大多数笔不是一次性的,默认勾上会让常态月均失去意义
    const checked = await ui.page.isChecked('input[name="oneOff"]').catch(() => false);
    await ui.assert(checked === false, '默认不勾(勾上才是例外)',
                    `实得 checked=${checked}`);

    // ── 4.5 · dashboard 上的即时块要【找得到】────────────────────────────
    report.section('4.5 · dashboard 本月支出分析(维护者追加)');

    /* 前置:这一块只在「本月至今有逐笔支出」时出现。beta 跨月之后(10-01 实际撞上)新的一期一笔都没有,
       这一节就整段红 —— 那是数据不是代码(在已发布 v1.28.2 上同样红)。没有的话补一笔(不动余额),cleanup 删掉。 */
    const cur = fx.currentPeriod();
    const has = db.num(`SELECT COUNT(*) FROM cash_flow c JOIN account a ON a.id=c.account_id
                         WHERE a.family_id=${fx.FAM} AND c.period_id=${cur} AND c.kind='EXPENSE' AND c.deleted_at IS NULL`);
    if (has === 0) {
      db.raw(`INSERT INTO cash_flow(period_id, account_id, kind, category_code, amount, occurred_at, note, submitted_by,
                                    is_adjustment, source_tag, expense_category_id, affects_balance, one_off)
              SELECT ${cur}, c.account_id, c.kind, c.category_code, 123.45, CURDATE(), '${SEED22}', c.submitted_by,
                     0, c.source_tag, c.expense_category_id, 0, 0
                FROM cash_flow c JOIN account a ON a.id=c.account_id
               WHERE a.family_id=${fx.FAM} AND c.kind='EXPENSE' AND c.deleted_at IS NULL AND c.is_adjustment=0
                 AND c.expense_category_id IS NOT NULL
               ORDER BY c.id DESC LIMIT 1`);
      report.info('前置:本期还没有逐笔支出(刚跨月)· 补了一笔不动余额的,cleanup 删掉');
    }

    await ui.goto('/dashboard');
    await ui.rendered('仪表盘');
    /* 【必须是个有标题的整宽块】——
       第一版做成「人赚」卡内的一条 5px 色带、半栏宽、没有标题,
       维护者连问三次「放哪了」。做了但找不到 = 没做。
       用 h3 断言而不是纯文本:有没有标题决定了它在页面结构里找不找得到。 */
    await ui.count('h3:text-is("本月支出 · 砍得掉吗")', 1, 'dashboard 上是个有标题的独立块');
    await ui.seesText('本月至今 · 日常开支', '窗口与基数写在脸上,不只藏在 ⓘ 里');
    await ui.seesText('看近 12 期的完整分析', '给了去报表看完整分析的出口');
    /* 口径必须与报表那一章【明确区分】:那里锚最近已定稿期,这里是本月至今。
       不标的话同一个「刚性占比」两页给不同的数,用户无从判断哪个对。 */
    await ui.seesText('这一块不给好坏评价', '不给好坏评价 —— 花钱没有对错');

    // ── 5 · 开关关掉之后【别处也一起消失】(FR-674)──────────────────────
    report.section('5 · 总开关级联(FR-674)');

    await ui.goto('/admin/calc-tweaks');
    await ui.rendered('管理页');
    await ui.seesText('报表里的支出分析', '开关在管理页上,名字是家人看得懂的词');
    await ui.notSeesText('模块开关', '开关文案不用技术词');

    await ui.page.uncheck('input[name="expenseAnalysisOn"]').catch(() => {});
    await ui.submit('form[action$="/calc-tweaks/expense-analysis"] button[type=submit]',
                    '关掉支出分析');
    await ui.seesText('已关闭', '保存后给了回执');

    // 关掉之后:报表页那三块不见了
    await ui.goto('/reports');
    await ui.rendered('报表页(开关关闭后)');
    await ui.notSeesText('这些钱砍得掉吗', '三分块消失了');
    // 【不能用「为什么变了」】—— 它是既有的「净资产为什么变了」的子串,
    //   那一块跟支出分析没关系、永远都在,判据会恒假。
    await ui.notSeesText('这个月为什么', '归因瀑布消失了');
    // 【判据要挑一句「开着时一定在」的话】—— 原来挑的是「斜纹段 = 一次性」,
    //   而那行只在瀑布里真有一次性段时才出现,于是判据在没有一次性段的数据上恒真。
    await ui.notSeesText('金额差是主信息', '瀑布的图例也一起没了');

    // 【而且别处的回流读数一起消失】—— 这是这条开关最容易做成半开的地方。
    //   「我关了支出分析,怎么别处还在冒常态月均」是最让人困惑的一种半开。
    await ui.goto('/checkup');
    await ui.rendered('体检页(开关关闭后)');
    await ui.notSeesText('按 · 常 · 态 · 月 · 均', '体检页的并列读数也一起没了');
    await ui.goto('/dashboard');
    await ui.rendered('仪表盘(开关关闭后)');
    await ui.notSeesText('按常态月均', '紧急储备 tooltip 里的并列读数也一起没了');
    await ui.count('h3:text-is("本月支出 · 砍得掉吗")', 0, 'dashboard 上那块也一起没了');

    const cfgOff = db.one(
      `SELECT value_text FROM family_runtime_config
        WHERE family_id=${fx.FAM} AND key_name='expense_analysis_enabled'`);
    await ui.assert(cfgOff === 'false', '真值层:开关确实落库了',
                    `实得 ${cfgOff}`);

    // 打开还原
    await ui.goto('/admin/calc-tweaks');
    await ui.page.check('input[name="expenseAnalysisOn"]').catch(() => {});
    await ui.submit('form[action$="/calc-tweaks/expense-analysis"] button[type=submit]',
                    '重新打开支出分析');
    await ui.seesText('已打开', '开关能拨回来');

    await ui.noConsoleErrors('全程无控制台报错');
  },

  /**
   * 还原<b>声明终态</b>,不依赖「跑之前是什么样」。
   *
   * 踩过:还原写成「if(跑之前是 X) 改回 X」,连跑两次时第二次读到的已经是改后的值,
   * 整段还原被跳过,beta 被留在中间状态里 —— 下一个人跑别的回归会莫名其妙。
   */
  async cleanup() {
    db.raw(`DELETE FROM cash_flow WHERE note='${SEED22}'`);
    // 终态:支出分析开着(删掉这条配置 = 回到代码默认的「开」)
    db.raw(`DELETE FROM family_runtime_config
             WHERE family_id=${fx.FAM} AND key_name='expense_analysis_enabled'`);
    // 终态:餐饮美食是弹性(V62 起步包给的预设)
    db.raw(`UPDATE expense_category SET expense_nature='FLEX'
             WHERE family_id=${fx.FAM} AND parent_id IS NULL AND name='餐饮美食'`);
  }
};
