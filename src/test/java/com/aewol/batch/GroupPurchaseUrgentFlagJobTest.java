package com.aewol.batch;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aewol.domain.grouppurchase.mapper.GroupPurchaseMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupPurchaseUrgentFlagJobTest {

    @Mock GroupPurchaseMapper groupPurchaseMapper;
    @InjectMocks GroupPurchaseUrgentFlagJob job;

    @Test
    @DisplayName("실행하면 마감 지난 is_urgent_active 갱신을 매퍼에 위임한다")
    void should_delegateToMapper_onRun() {
        when(groupPurchaseMapper.deactivateExpiredUrgentFlags()).thenReturn(3);

        job.deactivateExpiredUrgentFlags();

        verify(groupPurchaseMapper).deactivateExpiredUrgentFlags();
    }
}
