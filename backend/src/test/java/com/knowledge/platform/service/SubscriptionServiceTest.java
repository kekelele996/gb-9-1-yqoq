package com.knowledge.platform.service;

import com.knowledge.platform.entity.Column;
import com.knowledge.platform.entity.Order;
import com.knowledge.platform.entity.Subscription;
import com.knowledge.platform.repository.ColumnRepository;
import com.knowledge.platform.repository.OrderRepository;
import com.knowledge.platform.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private ColumnRepository columnRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private Order buildOrder(Subscription.Plan plan) {
        Order order = new Order();
        order.setId("order-1");
        order.setOrderNo("NO-1");
        order.setUserId("user-1");
        order.setItemId("column-1");
        order.setItemTitle("专栏");
        order.setPlan(plan);
        order.setAmount(new BigDecimal("100"));
        return order;
    }

    private Subscription captureSaved() {
        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void activate_firstSubscription_createsActiveAndIncrementsCount() {
        Order order = buildOrder(Subscription.Plan.MONTHLY);
        when(subscriptionRepository.findByUserIdAndColumnId("user-1", "column-1"))
                .thenReturn(Optional.empty());
        Column column = new Column();
        column.setId("column-1");
        column.setSubscriberCount(10);
        when(columnRepository.findById("column-1")).thenReturn(Optional.of(column));

        subscriptionService.activateSubscription(order);

        Subscription saved = captureSaved();
        assertEquals(Subscription.Status.ACTIVE, saved.getStatus());
        assertNotNull(saved.getOrderId(), "订阅必须关联支付订单");
        assertTrue(saved.getEndDate().isAfter(LocalDateTime.now().plusDays(27)));
        assertTrue(saved.getEndDate().isBefore(LocalDateTime.now().plusDays(32)));
        assertEquals(11, column.getSubscriberCount(), "首次订阅订阅人数 +1");
    }

    @Test
    void activate_whenActiveExists_stacksOnOriginalEndDate_noSecondSubscription() {
        Order order = buildOrder(Subscription.Plan.QUARTERLY);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime originalStart = now.minusMonths(1);
        LocalDateTime originalEnd = now.plusMonths(2);

        Subscription existing = new Subscription();
        existing.setId("sub-1");
        existing.setUserId("user-1");
        existing.setColumnId("column-1");
        existing.setPlan(Subscription.Plan.MONTHLY);
        existing.setStartDate(originalStart);
        existing.setEndDate(originalEnd);
        existing.setStatus(Subscription.Status.ACTIVE);
        when(subscriptionRepository.findByUserIdAndColumnId("user-1", "column-1"))
                .thenReturn(Optional.of(existing));

        subscriptionService.activateSubscription(order);

        Subscription saved = captureSaved();
        // 续接原到期日：2 个月剩余 + 3 个月 = 约 5 个月后到期
        assertTrue(saved.getEndDate().isAfter(now.plusMonths(4).plusDays(27)),
                "新方案必须续接原到期日，实际 endDate=" + saved.getEndDate());
        assertTrue(saved.getEndDate().isBefore(now.plusMonths(5).plusDays(2)));
        // 起始时间保持不变，且是同一条订阅
        assertEquals("sub-1", saved.getId());
        assertEquals(originalStart, saved.getStartDate());
        // 续期不重复增加订阅人数
        verify(columnRepository, never()).save(any());
    }

    @Test
    void activate_whenExpired_restartsFromNow_doesNotStackOnPastEndDate() {
        Order order = buildOrder(Subscription.Plan.YEARLY);
        LocalDateTime now = LocalDateTime.now();
        Subscription expired = new Subscription();
        expired.setId("sub-2");
        expired.setUserId("user-1");
        expired.setColumnId("column-1");
        expired.setPlan(Subscription.Plan.MONTHLY);
        expired.setStartDate(now.minusMonths(3));
        expired.setEndDate(now.minusMonths(2));
        expired.setStatus(Subscription.Status.EXPIRED);
        when(subscriptionRepository.findByUserIdAndColumnId("user-1", "column-1"))
                .thenReturn(Optional.of(expired));

        subscriptionService.activateSubscription(order);

        Subscription saved = captureSaved();
        assertEquals(Subscription.Status.ACTIVE, saved.getStatus());
        // 从当前时间起 1 年，而不是在两个月前的到期日上累加
        assertTrue(saved.getEndDate().isAfter(now.plusYears(1).minusSeconds(5)));
        assertTrue(saved.getEndDate().isBefore(now.plusYears(1).plusSeconds(5)));
        verify(columnRepository, never()).save(any());
    }
}
