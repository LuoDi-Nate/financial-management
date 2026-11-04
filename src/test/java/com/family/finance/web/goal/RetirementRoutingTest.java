package com.family.finance.web.goal;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RetirementRoutingTest {
    @Test void newRetirementEntryIsNotParsedAsNumericGoalId() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(mock(GoalController.class, CALLS_REAL_METHODS), mock(RetirementController.class))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/goals/new/retirement")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/goals/retirement/new"));
    }
}
