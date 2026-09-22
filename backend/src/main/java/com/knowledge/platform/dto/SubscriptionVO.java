package com.knowledge.platform.dto;

import com.knowledge.platform.entity.Subscription;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订阅视图对象：在订阅记录基础上补充专栏与对应订单信息，
 * 保证“我的订阅”与“我的订单”展示同一笔结果（金额、有效期一致）。
 */
@Data
public class SubscriptionVO {
    private String id;

    private String userId;

    private String columnId;

    private String columnTitle;

    private String columnCover;

    private String columnCategory;

    private Subscription.Plan plan;

    private LocalDateTime startDate;

    private LocalDateTime endDate;

    private Subscription.Status status;

    /** 综合 endDate 计算出的实际状态：已过期 / 有效 */
    private boolean active;

    private String orderId;

    private String orderNo;

    private BigDecimal amount;

    private LocalDateTime createdAt;
}
