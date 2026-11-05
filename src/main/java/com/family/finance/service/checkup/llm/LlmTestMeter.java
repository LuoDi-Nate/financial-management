package com.family.finance.service.checkup.llm;

import com.family.finance.repository.LlmTestMeterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LlmTestMeter {
    private final LlmTestMeterMapper mapper;

    /** A rejected import or model operation must not roll its interception receipt back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long ownerFamilyId, String epoch, String surface, boolean blocked) {
        if (mapper.upsertOwned(ownerFamilyId, epoch, surface, blocked) == 0)
            throw new IllegalStateException("测试环境调用计数无法记录,已停止模型请求");
    }
}
