package com.knowledge.platform.service;

import com.knowledge.platform.dto.ApiResponse;
import com.knowledge.platform.entity.Column;
import com.knowledge.platform.entity.Subscription;
import com.knowledge.platform.repository.ColumnRepository;
import com.knowledge.platform.repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SubscriptionService {
    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ColumnRepository columnRepository;

    public ApiResponse<Page<Subscription>> getMySubscriptions(String userId, Pageable pageable) {
        expireOverdueSubscriptions(userId);
        Page<Subscription> subscriptions = subscriptionRepository.findByUserId(userId, pageable);

        List<String> columnIds = subscriptions.getContent().stream()
                .map(Subscription::getColumnId)
                .distinct()
                .toList();
        Map<String, Column> columnMap = columnRepository.findAllById(columnIds).stream()
                .collect(Collectors.toMap(Column::getId, Function.identity()));
        subscriptions.getContent().forEach(sub -> sub.setColumn(columnMap.get(sub.getColumnId())));

        return ApiResponse.success(subscriptions);
    }

    /**
     * 支付成功后开通/续费订阅。
     * 已有未过期订阅时在原到期日基础上续接，保证同一专栏只有一个有效订阅；
     * 仅新订阅时增加专栏订阅人数。
     */
    @Transactional
    public Subscription activateSubscription(String userId, String columnId, String planStr, String orderId) {
        Subscription.Plan plan = Subscription.Plan.valueOf(planStr.toUpperCase());
        LocalDateTime now = LocalDateTime.now();

        Optional<Subscription> existingOpt = subscriptionRepository.findByUserIdAndColumnIdAndStatus(
                userId, columnId, Subscription.Status.ACTIVE
        );

        if (existingOpt.isPresent()) {
            Subscription existing = existingOpt.get();
            if (existing.getEndDate() != null && existing.getEndDate().isAfter(now)) {
                // 续费：从原到期日续接，不新建订阅记录
                existing.setEndDate(plusPlan(existing.getEndDate(), plan));
                existing.setPlan(plan);
                existing.setOrderId(orderId);
                existing.setUpdatedAt(now);
                return subscriptionRepository.save(existing);
            }
            // 已过期但未标记，先归档再新建
            existing.setStatus(Subscription.Status.EXPIRED);
            existing.setUpdatedAt(now);
            subscriptionRepository.save(existing);
        }

        Subscription subscription = new Subscription();
        subscription.setUserId(userId);
        subscription.setColumnId(columnId);
        subscription.setPlan(plan);
        subscription.setStartDate(now);
        subscription.setEndDate(plusPlan(now, plan));
        subscription.setStatus(Subscription.Status.ACTIVE);
        subscription.setOrderId(orderId);
        subscription.setCreatedAt(now);
        subscription.setUpdatedAt(now);
        subscription = subscriptionRepository.save(subscription);

        columnRepository.findById(columnId).ifPresent(column -> {
            column.setSubscriberCount(column.getSubscriberCount() + 1);
            column.setUpdatedAt(now);
            columnRepository.save(column);
        });

        return subscription;
    }

    private LocalDateTime plusPlan(LocalDateTime base, Subscription.Plan plan) {
        return switch (plan) {
            case MONTHLY -> base.plusMonths(1);
            case QUARTERLY -> base.plusMonths(3);
            case YEARLY -> base.plusYears(1);
        };
    }

    private void expireOverdueSubscriptions(String userId) {
        List<Subscription> actives = subscriptionRepository.findByUserIdAndStatus(
                userId, Subscription.Status.ACTIVE
        );
        LocalDateTime now = LocalDateTime.now();
        for (Subscription sub : actives) {
            if (sub.getEndDate() != null && sub.getEndDate().isBefore(now)) {
                sub.setStatus(Subscription.Status.EXPIRED);
                sub.setUpdatedAt(now);
                subscriptionRepository.save(sub);
            }
        }
    }
}
