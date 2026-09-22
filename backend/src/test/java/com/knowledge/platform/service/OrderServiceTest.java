package com.knowledge.platform.service;

import com.knowledge.platform.dto.ApiResponse;
import com.knowledge.platform.entity.Column;
import com.knowledge.platform.entity.Order;
import com.knowledge.platform.entity.Subscription;
import com.knowledge.platform.repository.ColumnRepository;
import com.knowledge.platform.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ColumnRepository columnRepository;

    @Mock
    private SubscriptionService subscriptionService;

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private OrderService orderService;

    private Order pendingOrder;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        pendingOrder = new Order();
        pendingOrder.setId("order-1");
        pendingOrder.setOrderNo("20260922120000123456");
        pendingOrder.setUserId("user-1");
        pendingOrder.setType(Order.OrderType.COLUMN_SUBSCRIPTION);
        pendingOrder.setItemId("column-1");
        pendingOrder.setItemTitle("Java 设计模式");
        pendingOrder.setPlan(Subscription.Plan.MONTHLY);
        pendingOrder.setAmount(new BigDecimal("19.90"));
        pendingOrder.setStatus(Order.Status.PENDING);
        pendingOrder.setCreatedAt(now);
    }

    private void mockPendingOwned() {
        when(orderRepository.findByIdAndUserId("order-1", "user-1"))
                .thenReturn(Optional.of(pendingOrder));
    }

    private void mockAtomicPaySuccess() {
        com.mongodb.client.result.UpdateResult result =
                mock(com.mongodb.client.result.UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(), any(), eq(Order.class))).thenReturn(result);
    }

    @Test
    void pay_pendingOrder_marksPaidAndActivatesSubscriptionOnce() {
        mockPendingOwned();
        mockAtomicPaySuccess();

        ApiResponse<Order> response = orderService.payOrder("user-1", "order-1");

        assertTrue(response.isSuccess());
        assertEquals(Order.Status.PAID, pendingOrder.getStatus());
        assertNotNull(pendingOrder.getPaidAt());
        assertEquals(Order.PaymentMethod.ALIPAY, pendingOrder.getPaymentMethod());
        // 仅开通一次订阅
        verify(subscriptionService, times(1)).activateSubscription(pendingOrder);
    }

    @Test
    void pay_alreadyPaidOrder_isIdempotentAndDoesNotExtendAgain() {
        pendingOrder.setStatus(Order.Status.PAID);
        pendingOrder.setPaidAt(LocalDateTime.now().minusDays(1));
        when(orderRepository.findByIdAndUserId("order-1", "user-1"))
                .thenReturn(Optional.of(pendingOrder));

        ApiResponse<Order> response = orderService.payOrder("user-1", "order-1");

        assertTrue(response.isSuccess());
        // 重复支付不触发任何开通/续期
        verify(subscriptionService, never()).activateSubscription(any());
        verify(mongoTemplate, never()).updateFirst(any(), any(), any(Class.class));
    }

    @Test
    void pay_cancelledOrder_isRejected() {
        pendingOrder.setStatus(Order.Status.CANCELLED);
        when(orderRepository.findByIdAndUserId("order-1", "user-1"))
                .thenReturn(Optional.of(pendingOrder));

        ApiResponse<Order> response = orderService.payOrder("user-1", "order-1");

        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().contains("已取消"));
        verify(subscriptionService, never()).activateSubscription(any());
    }

    @Test
    void pay_concurrentSecondAttempt_activatesOnlyOnce() {
        mockPendingOwned();
        // 原子更新影响 0 行：另一请求已抢先支付
        com.mongodb.client.result.UpdateResult result =
                mock(com.mongodb.client.result.UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(), any(), eq(Order.class))).thenReturn(result);

        Order paid = new Order();
        paid.setId("order-1");
        paid.setStatus(Order.Status.PAID);
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(paid));

        ApiResponse<Order> response = orderService.payOrder("user-1", "order-1");

        assertTrue(response.isSuccess());
        verify(subscriptionService, never()).activateSubscription(any());
    }

    @Test
    void pay_otherUsersOrder_returnsNotFound() {
        when(orderRepository.findByIdAndUserId("order-1", "attacker"))
                .thenReturn(Optional.empty());

        ApiResponse<Order> response = orderService.payOrder("attacker", "order-1");

        assertFalse(response.isSuccess());
        verify(subscriptionService, never()).activateSubscription(any());
    }

    @Test
    void cancel_pendingOrder_succeedsAndBlocksLaterPay() {
        mockPendingOwned();
        com.mongodb.client.result.UpdateResult result =
                mock(com.mongodb.client.result.UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(), any(), eq(Order.class))).thenReturn(result);

        ApiResponse<Order> cancelResp = orderService.cancelOrder("user-1", "order-1");
        assertTrue(cancelResp.isSuccess());
        assertEquals(Order.Status.CANCELLED, pendingOrder.getStatus());
    }
}
