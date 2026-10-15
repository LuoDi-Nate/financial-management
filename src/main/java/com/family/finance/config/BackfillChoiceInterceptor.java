package com.family.finance.config;

import com.family.finance.service.backfill.BackfillContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * v1.34.2 · 把页面上「补记到历史期:A 只补记 / B 之后各期一起挪」的选择(参数 backfillMode)放进
 * {@link BackfillContext},请求结束清掉。没有这个参数 = 默认 A(维护者拍板)。
 */
@Component
public class BackfillChoiceInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        BackfillContext.clear();
        BackfillContext.request(request.getParameter("backfillMode"));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BackfillContext.clear();
    }
}
