package com.knowledge.platform.service;

import com.knowledge.platform.dto.ApiResponse;
import com.knowledge.platform.dto.SubscriptionVO;
import com.knowledge.platform.entity.Column;
import com.knowledge.platform.entity.Order;
import com.knowledge.platform.entity.Subscription;
import com.knowledge.platform.repository.ColumnRepository;
import com.knowledge.platform.repository.OrderRepository;
import com.knowledge.platform.repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class SubscriptionService {
    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private ColumnRepository columnRepository;

    @Autowired
    private OrderRepository orderRepository;

    public ApiResponse<Page<SubscriptionVO>> getMySubscriptions(String userId, Pageable pageable) {
        Page<Subscription> page = subscriptionRepository.findByUserId(userId, pageable);

        Set<String> columnIds = new HashSet<>();
        Set<String> orderIds = new HashSet<>();
        for (Subscription subscription : page.getContent()) {
            if (subscription.getColumnId() != null) {
                columnIds.add(subscription.getColumnId());
            }
            if (subscription.getOrderId() != null) {
                orderIds.add(subscription.getOrderId());
            }
        }

        Map<String, Column> columnMap = new HashMap<>();
        for (Column column : columnRepository.findAllById(columnIds)) {
            columnMap.put(column.getId(), column);
        }
        Map<String, Order> orderMap = new HashMap<>();
        for (Order order : orderRepository.findAllById(orderIds)) {
            orderMap.put(order.getId(), order);
        }

        List<SubscriptionVO> voList = new ArrayList<>();
        for (Subscription subscription : page.getContent()) {
            voList.add(toVO(
                    subscription,
                    columnMap.get(subscription.getColumnId()),
                    orderMap.get(subscription.getOrderId())));
        }
        return ApiResponse.success(new PageImpl<>(voList, pageable, page.getTotalElements()));
    }

    /**
     * 查询当前用户对某专栏的最新订阅（用于专栏详情展示订阅/支付结果）。
     * 返回 null 表示从未订阅。
     */
    public SubscriptionVO getMySubscription(String userId, String columnId) {
        List<Subscription> subscriptions = subscriptionRepository
                .findByUserIdOrderByCreatedAtDesc(userId);
        for (Subscription subscription : subscriptions) {
            if (subscription.getColumnId().equals(columnId)) {
                return buildVO(subscription);
            }
        }
        return null;
    }

    /**
     * 订单支付成功后开通/续期订阅。
     * <p>
     * 每个用户对同一专栏只保留一条订阅记录：
     * <ul>
     *     <li>已有未过期订阅时，新方案在原到期日基础上续接（不产生第二条有效订阅）；</li>
     *     <li>订阅已过期时，从当前时间重新开始计算有效期；</li>
     *     <li>首次订阅才增加专栏订阅人数，续期不重复增加。</li>
     * </ul>
     */
    @Transactional
    public Subscription activateSubscription(Order order) {
        LocalDateTime now = LocalDateTime.now();
        Subscription.Plan plan = order.getPlan();

        Subscription subscription = subscriptionRepository
                .findByUserIdAndColumnId(order.getUserId(), order.getItemId())
                .orElse(null);

        boolean isNew = subscription == null;
        if (isNew) {
            subscription = new Subscription();
            subscription.setUserId(order.getUserId());
            subscription.setColumnId(order.getItemId());
            subscription.setStartDate(now);
            subscription.setEndDate(planEndDate(now, plan));
            subscription.setCreatedAt(now);
        } else {
            boolean stillActive = subscription.getEndDate() != null
                    && subscription.getEndDate().isAfter(now);
            if (!stillActive) {
                // 已过期：从当前时间重新开始计算有效期
                subscription.setStartDate(now);
                subscription.setEndDate(planEndDate(now, plan));
            } else {
                // 未过期：在原到期日基础上续接
                subscription.setEndDate(planEndDate(subscription.getEndDate(), plan));
            }
            subscription.setStatus(Subscription.Status.ACTIVE);
        }
        subscription.setPlan(plan);
        subscription.setOrderId(order.getId());
        subscription.setUpdatedAt(now);
        subscription = subscriptionRepository.save(subscription);

        if (isNew) {
            columnRepository.findById(order.getItemId()).ifPresent(column -> {
                column.setSubscriberCount(column.getSubscriberCount() + 1);
                columnRepository.save(column);
            });
        }
        return subscription;
    }

    /**
     * 依据套餐计算到期时间。
     */
    public static LocalDateTime planEndDate(LocalDateTime base, Subscription.Plan plan) {
        return switch (plan) {
            case MONTHLY -> base.plusMonths(1);
            case QUARTERLY -> base.plusMonths(3);
            case YEARLY -> base.plusYears(1);
        };
    }

    private SubscriptionVO buildVO(Subscription subscription) {
        Column column = subscription.getColumnId() == null
                ? null
                : columnRepository.findById(subscription.getColumnId()).orElse(null);
        Order order = subscription.getOrderId() == null
                ? null
                : orderRepository.findById(subscription.getOrderId()).orElse(null);
        return toVO(subscription, column, order);
    }

    private SubscriptionVO toVO(Subscription subscription, Column column, Order order) {
        SubscriptionVO vo = new SubscriptionVO();
        vo.setId(subscription.getId());
        vo.setUserId(subscription.getUserId());
        vo.setColumnId(subscription.getColumnId());
        vo.setPlan(subscription.getPlan());
        vo.setStartDate(subscription.getStartDate());
        vo.setEndDate(subscription.getEndDate());
        vo.setStatus(subscription.getStatus());
        vo.setActive(subscription.getEndDate() != null
                && subscription.getEndDate().isAfter(LocalDateTime.now()));
        vo.setOrderId(subscription.getOrderId());
        vo.setCreatedAt(subscription.getCreatedAt());

        // 批量补全专栏与订单信息（分页场景由调用方传入缓存）
        if (column == null && subscription.getColumnId() != null) {
            column = columnRepository.findById(subscription.getColumnId()).orElse(null);
        }
        if (column != null) {
            vo.setColumnTitle(column.getTitle());
            vo.setColumnCover(column.getCover());
            vo.setColumnCategory(column.getCategory());
        }
        if (order == null && subscription.getOrderId() != null) {
            order = orderRepository.findById(subscription.getOrderId()).orElse(null);
        }
        if (order != null) {
            vo.setOrderNo(order.getOrderNo());
            vo.setAmount(order.getAmount());
        }
        return vo;
    }
}
