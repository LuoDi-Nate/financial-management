package com.family.finance.web.goal;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.goal.GoalForecastForm;
import com.family.finance.service.goal.*;
import com.family.finance.service.AuditLogService;
import com.family.finance.domain.audit.AuditLogType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@Controller
@RequiredArgsConstructor
public class GoalForecastController {
    private final GoalService goals;
    private final GoalProgressService progress;
    private final GoalForecastService forecasts;
    private final AuditLogService audit;

    @InitBinder("estimate")
    void bind(WebDataBinder binder) { binder.setAllowedFields("estimateSource","estimateMonthlyAmount","estimateWishMonth"); }

    @PostMapping("/goals/{id:[0-9]+}/estimate/preview")
    public Object preview(@AuthenticationPrincipal MemberPrincipal me,@PathVariable long id,
                          @ModelAttribute("estimate") GoalForecastForm estimate,Model model) {
        var gp=owned(me,id); if(gp==null) return ResponseEntity.notFound().build();
        var view=forecasts.evaluate(gp.goal(),gp.pv(),gp.target(),gp.progress(),gp.retirement(),estimate,forecasts.context(me.getFamilyId(),LocalDate.now()));
        model.addAttribute("forecast",view); model.addAttribute("estimateGoalId",id);
        return "goals/_estimate :: result(${forecast})";
    }

    @PostMapping("/goals/{id:[0-9]+}/estimate")
    public Object save(@AuthenticationPrincipal MemberPrincipal me,@PathVariable long id,
                       @ModelAttribute("estimate") GoalForecastForm estimate,
                       @RequestHeader(value="X-Goal-Estimate",required=false) String ajax,Model model) {
        var gp=owned(me,id); if(gp==null) return ResponseEntity.notFound().build();
        if(!estimate.errors().isEmpty()) {
            if(ajax!=null) return ResponseEntity.badRequest().body("请检查时间估算的字段");
            return preview(me,id,estimate,model);
        }
        forecasts.save(me.getFamilyId(),id,estimate);
        audit.record(me.getFamilyId(),me.getMemberId(),AuditLogType.SYSTEM,"family_goal",id,"保存时间估算偏好 · "+estimate.getEstimateSource());
        if(ajax!=null) return ResponseEntity.noContent().build();
        return "redirect:/goals/"+id+(gp.isNewRetirement()?"/retirement?tab=plan":"#goal-estimate-settings");
    }

    private GoalProgressService.GoalProgress owned(MemberPrincipal me,long id) {
        var goal=goals.findActiveByFamily(me.getFamilyId()).stream().filter(g->g.getId()==id).findFirst();
        if(goal.isEmpty()) return null;
        var gp=progress.compute(me.getFamilyId(),goal.get());
        return GoalForecastService.supports(gp.goal(),gp.retirement())?gp:null;
    }
}
