package com.example.urlshortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClickService}, no Spring context. {@link ClickCounterRepository} is
 * mocked, mirroring how {@code RateLimitingAdmissionControlTest} exercises its collaborator-free
 * class directly.
 */
class ClickServiceTest {

    private final ClickCounterRepository repository = mock(ClickCounterRepository.class);

    private final ClickService clickService = new ClickService(repository);

    @Test
    void recordClickDelegatesToTheAtomicIncrement() {
        clickService.recordClick(125L);

        verify(repository).increment(125L);
    }

    @Test
    void clickCountReturnsTheStoredCounterValue() {
        ClickCounter counter = mock(ClickCounter.class);
        given(counter.getClickCount()).willReturn(7L);
        given(repository.findById(125L)).willReturn(Optional.of(counter));

        assertThat(clickService.clickCount(125L)).isEqualTo(7L);
    }

    @Test
    void clickCountIsZeroForAnUnknownLinkIdRatherThanThrowing() {
        given(repository.findById(999L)).willReturn(Optional.empty());

        assertThat(clickService.clickCount(999L)).isZero();
        verify(repository, never()).increment(eq(999L));
    }
}
