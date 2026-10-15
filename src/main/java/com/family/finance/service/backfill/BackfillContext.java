package com.family.finance.service.backfill;

/**
 * v1.34.2 · 往「之后已有人确认过余额」的账期里补记收支 / 划转时,<b>这一次请求</b>用户选的方式
 * (维护者 2026-10-15 拍板:每次问,默认 A)。
 *
 * <p>为什么放线程上下文而不是给十几个 EntryService 方法加参数:补记要经过的写入口很多
 * (收入 / 支出 / 快捷支出 / 划转 / 快捷划转 / 固定收支确认 / 删流水 / 删划转 …),
 * 它们最后都汇到 {@code EntryService.creditAccountBalance} 这一个口子;选择只在那里读。
 * 和 {@code SqlProfileContext} 同一个做法:拦截器在请求开始时放、结束时清,不跨请求。</p>
 *
 * <ul>
 *   <li>{@link Mode#KEEP} = A · 只补记这笔,余额不动(当时填的余额已经包含了它)—— <b>默认</b></li>
 *   <li>{@link Mode#SHIFT} = B · 当时的余额还没算上它:这一期和之后每一期这个账户的余额一起挪</li>
 * </ul>
 * 只对「之后有确认过的余额」的账户生效;其余账户照旧(改这一期 + 传导到下一个系统代填的期)。
 */
public final class BackfillContext {

    public enum Mode { KEEP, SHIFT }

    private static final class State {
        Mode requested;   // 页面上选的(没选 = null → 默认 KEEP)
        Mode override;    // 删除时:按这一笔当初记的方式撤回
        Mode applied;     // 这次请求里真的按 A / B 处理过 → 写进新记录,删时照此撤回
    }

    private static final ThreadLocal<State> TL = ThreadLocal.withInitial(State::new);

    private BackfillContext() {}

    /** 页面参数 backfillMode=KEEP|SHIFT;别的值当没选 */
    public static void request(String raw) {
        TL.get().requested = parse(raw);
    }

    public static Mode parse(String raw) {
        if (raw == null) return null;
        try { return Mode.valueOf(raw.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }

    /** 删除时用:按这一笔当初记的方式撤回;null = 当初是普通记账 */
    public static void override(Mode m) { TL.get().override = m; }

    /** 这个账户要按 A / B 处理时取方式:删除按当初的;否则页面选的;都没有 → 默认 A */
    public static Mode resolve() {
        State s = TL.get();
        if (s.override != null) return s.override;
        return s.requested != null ? s.requested : Mode.KEEP;
    }

    public static void markApplied(Mode m) {
        State s = TL.get();
        if (s.applied == null || m == Mode.SHIFT) s.applied = m;
    }

    /** 新记录要存的方式:这次请求里按 A / B 处理过就存它,普通记账存 null */
    public static String appliedName() {
        Mode m = TL.get().applied;
        return m == null ? null : m.name();
    }

    /** 一笔记录写完、开始下一笔之前清掉「处理过」的标记(一次请求可能记好几笔) */
    public static void resetApplied() {
        State s = TL.get();
        s.applied = null;
        s.override = null;
    }

    public static void clear() { TL.remove(); }
}
