package com.knowledge.platform.service;

import com.knowledge.platform.dto.ApiResponse;
import com.knowledge.platform.dto.CreateOrderRequest;
import com.knowledge.platform.entity.Column;
import com.knowledge.platform.entity.Order;
import com.knowledge.platform.entity.Subscription;
import com.knowledge.platform.repository.ColumnRepository;
import com.knowledge.platform.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ColumnRepository columnRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    public ApiResponse<Page<Order>> getMyOrders(String userId, Pageable pageable) {
        return ApiResponse.success(orderRepository.findByUserId(userId, pageable));
    }

    public ApiResponse<Order> getOrder(String userId, String orderId) {
        Optional<Order> orderOpt = orderRepository.findByIdAndUserId(orderId, userId);
        return orderOpt.map(ApiResponse::success).orElseGet(() -> ApiResponse.error("订单不存在"));
    }

    public ApiResponse<Order> createOrder(String userId, CreateOrderRequest request) {
        if (request.getType() == null) {
            return ApiResponse.error("订单类型不能为空");
        }
        Order.OrderType type;
        try {
            type = Order.OrderType.valueOf(request.getType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的订单类型");
        }
        if (type != Order.OrderType.COLUMN_SUBSCRIPTION) {
            return ApiResponse.error("暂不支持该类型订单");
        }
        return createColumnSubscriptionOrder(userId, request.getItemId(), request.getPlan());
    }

    /**
     * 创建专栏订阅待支付订单，金额按订阅方案取自专栏定价。
     */
    @Transactional
    public ApiResponse<Order> createColumnSubscriptionOrder(String userId, String columnId, String planStr) {
        Optional<Column> columnOpt = columnRepository.findById(columnId);
        if (columnOpt.isEmpty()) {
            return ApiResponse.error("专栏不存在");
        }
        Column column = columnOpt.get();
        if (column.getStatus() != Column.Status.PUBLISHED) {
            return ApiResponse.error("专栏未发布，无法订阅");
        }
        if (column.getCreatorId() != null && column.getCreatorId().equals(userId)) {
            return ApiResponse.error("不能订阅自己的专栏");
        }

        Subscription.Plan plan;
        try {
            plan = Subscription.Plan.valueOf(planStr == null ? "" : planStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的订阅方案");
        }

        BigDecimal amount = switch (plan) {
            case MONTHLY -> column.getMonthlyPrice();
            case QUARTERLY -> column.getQuarterlyPrice();
            case YEARLY -> column.getYearlyPrice();
        };
        if (amount == null) {
            return ApiResponse.error("该订阅方案暂未定价");
        }

        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setType(Order.OrderType.COLUMN_SUBSCRIPTION);
        order.setItemId(columnId);
        order.setItemTitle(column.getTitle() + "（" + planLabel(plan) + "）");
        order.setPlan(plan.name());
        order.setAmount(amount);
        order.setStatus(Order.Status.PENDING);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.save(order);

        return ApiResponse.success("订单已创建，请尽快支付", order);
    }

    /**
     * 支付订单。重复支付同一订单只生效一次：已支付订单直接返回，
     * 不会重复延长订阅有效期或增加订阅人数；已取消订单不得支付。
     */
    @Transactional
    public ApiResponse<Order> pay(String userId, String orderId) {
        Optional<Order> orderOpt = orderRepository.findByIdAndUserId(orderId, userId);
        if (orderOpt.isEmpty()) {
            return ApiResponse.error("订单不存在");
        }
        Order order = orderOpt.get();

        switch (order.getStatus()) {
            case PAID:
                return ApiResponse.success("订单已支付，请勿重复支付", order);
            case CANCELLED:
                return ApiResponse.error("订单已取消，无法支付");
            case REFUNDED:
                return ApiResponse.error("订单已退款，无法支付");
            default:
                break;
        }

        LocalDateTime now = LocalDateTime.now();
        order.setStatus(Order.Status.PAID);
        order.setPaymentMethod(Order.PaymentMethod.ALIPAY);
        order.setPaymentId("ALI" + order.getOrderNo());
        order.setPaidAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.save(order);

        if (order.getType() == Order.OrderType.COLUMN_SUBSCRIPTION) {
            subscriptionService.activateSubscription(userId, order.getItemId(), order.getPlan(), order.getId());
        }

        return ApiResponse.success("支付成功，订阅已开通", order);
    }

    /**
     * 取消待支付订单；已支付订单不可取消。
     */
    @Transactional
    public ApiResponse<Order> cancel(String userId, String orderId) {
        Optional<Order> orderOpt = orderRepository.findByIdAndUserId(orderId, userId);
        if (orderOpt.isEmpty()) {
            return ApiResponse.error("订单不存在");
        }
        Order order = orderOpt.get();

        if (order.getStatus() == Order.Status.CANCELLED) {
            return ApiResponse.error("订单已取消");
        }
        if (order.getStatus() != Order.Status.PENDING) {
            return ApiResponse.error("仅待支付订单可以取消");
        }

        order.setStatus(Order.Status.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        order = orderRepository.save(order);
        return ApiResponse.success("订单已取消", order);
    }

    public ApiResponse<String> requestInvoice(String userId, String orderId) {
        Optional<Order> orderOpt = orderRepository.findByIdAndUserId(orderId, userId);
        if (orderOpt.isEmpty()) {
            return ApiResponse.error("订单不存在");
        }
        if (orderOpt.get().getStatus() != Order.Status.PAID) {
            return ApiResponse.error("仅已支付订单可以申请发票");
        }
        return ApiResponse.success("发票申请已提交", orderOpt.get().getOrderNo());
    }

    private String planLabel(Subscription.Plan plan) {
        return switch (plan) {
            case MONTHLY -> "月付";
            case QUARTERLY -> "季付";
            case YEARLY -> "年付";
        };
    }

    private String generateOrderNo() {
        String orderNo;
        do {
            orderNo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        } while (orderRepository.findByOrderNo(orderNo).isPresent());
        return orderNo;
    }
}
