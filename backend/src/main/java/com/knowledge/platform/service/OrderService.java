package com.knowledge.platform.service;

import com.knowledge.platform.dto.ApiResponse;
import com.knowledge.platform.dto.OrderCreateRequest;
import com.knowledge.platform.entity.Column;
import com.knowledge.platform.entity.Order;
import com.knowledge.platform.entity.Subscription;
import com.knowledge.platform.repository.ColumnRepository;
import com.knowledge.platform.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ColumnRepository columnRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private MongoTemplate mongoTemplate;

    public ApiResponse<Page<Order>> getMyOrders(String userId, Pageable pageable) {
        return ApiResponse.success(orderRepository.findByUserId(userId, pageable));
    }

    public ApiResponse<Order> getMyOrder(String userId, String orderId) {
        Optional<Order> orderOpt = orderRepository.findByIdAndUserId(orderId, userId);
        if (orderOpt.isEmpty()) {
            return ApiResponse.error("订单不存在");
        }
        return ApiResponse.success(orderOpt.get());
    }

    /**
     * 创建待支付订单（专栏订阅：月付/季付/年付）。
     */
    @Transactional
    public ApiResponse<Order> createOrder(String userId, OrderCreateRequest request) {
        if (request.getType() == null || request.getPlan() == null || request.getItemId() == null) {
            return ApiResponse.error("订单参数不完整");
        }
        if (!Order.OrderType.COLUMN_SUBSCRIPTION.name().equals(request.getType())) {
            return ApiResponse.error("暂不支持该类型订单");
        }

        Subscription.Plan plan;
        try {
            plan = Subscription.Plan.valueOf(request.getPlan().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的订阅套餐");
        }

        Optional<Column> columnOpt = columnRepository.findById(request.getItemId());
        if (columnOpt.isEmpty()) {
            return ApiResponse.error("专栏不存在");
        }
        Column column = columnOpt.get();
        if (column.getStatus() != Column.Status.PUBLISHED) {
            return ApiResponse.error("专栏未上架，无法订阅");
        }

        BigDecimal amount = switch (plan) {
            case MONTHLY -> column.getMonthlyPrice();
            case QUARTERLY -> column.getQuarterlyPrice();
            case YEARLY -> column.getYearlyPrice();
        };
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            return ApiResponse.error("该套餐价格未配置");
        }

        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setOrderNo(generateOrderNo(now));
        order.setUserId(userId);
        order.setType(Order.OrderType.COLUMN_SUBSCRIPTION);
        order.setItemId(column.getId());
        order.setItemTitle(column.getTitle());
        order.setPlan(plan);
        order.setAmount(amount);
        order.setStatus(Order.Status.PENDING);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        try {
            order = orderRepository.save(order);
        } catch (DuplicateKeyException e) {
            // orderNo 极小概率冲突，更换后重试一次
            order.setOrderNo(generateOrderNo(now));
            order = orderRepository.save(order);
        }
        return ApiResponse.success("订单创建成功", order);
    }

    /**
     * 模拟支付。
     * <p>
     * 通过原子条件更新保证同一订单重复支付只能生效一次：
     * 仅当订单仍为 PENDING 时才会转为 PAID，后续开通/续期订阅只执行一次，
     * 不会重复延长有效期或增加订阅人数。
     * 已取消、已退款订单不得支付；重复支付直接返回当前订单（幂等）。
     */
    @Transactional
    public ApiResponse<Order> payOrder(String userId, String orderId) {
        Optional<Order> orderOpt = orderRepository.findByIdAndUserId(orderId, userId);
        if (orderOpt.isEmpty()) {
            return ApiResponse.error("订单不存在");
        }
        Order order = orderOpt.get();

        if (order.getStatus() == Order.Status.CANCELLED) {
            return ApiResponse.error("订单已取消，无法支付");
        }
        if (order.getStatus() == Order.Status.REFUNDED) {
            return ApiResponse.error("订单已退款，无法支付");
        }
        if (order.getStatus() == Order.Status.PAID) {
            // 幂等：重复支付不重复开通/延长订阅
            return ApiResponse.success("该订单已支付，请勿重复支付", order);
        }

        LocalDateTime now = LocalDateTime.now();

        // 原子地将 PENDING -> PAID，并发/重复支付只有一个请求能更新成功
        Query query = new Query(Criteria.where("_id").is(orderId)
                .and("userId").is(userId)
                .and("status").is(Order.Status.PENDING));
        Update update = new Update()
                .set("status", Order.Status.PAID)
                .set("paymentMethod", Order.PaymentMethod.ALIPAY)
                .set("paymentId", generatePaymentId())
                .set("paidAt", now)
                .set("updatedAt", now);

        com.mongodb.client.result.UpdateResult result = mongoTemplate.updateFirst(query, update, Order.class);
        if (result.getModifiedCount() == 0) {
            // 已被其他支付请求处理或订单状态已变更，返回最新订单，不再执行开通逻辑
            Order latest = orderRepository.findById(orderId).orElse(order);
            if (latest.getStatus() == Order.Status.CANCELLED) {
                return ApiResponse.error("订单已取消，无法支付");
            }
            return ApiResponse.success("该订单已支付，请勿重复支付", latest);
        }

        // 仅首次支付成功执行一次：开通或续接订阅
        order.setStatus(Order.Status.PAID);
        order.setPaymentMethod(Order.PaymentMethod.ALIPAY);
        order.setPaidAt(now);
        subscriptionService.activateSubscription(order);

        return ApiResponse.success("支付成功，订阅已开通", order);
    }

    /**
     * 取消待支付订单。已支付/已取消订单不可取消。
     */
    @Transactional
    public ApiResponse<Order> cancelOrder(String userId, String orderId) {
        Optional<Order> orderOpt = orderRepository.findByIdAndUserId(orderId, userId);
        if (orderOpt.isEmpty()) {
            return ApiResponse.error("订单不存在");
        }
        Order order = orderOpt.get();
        if (order.getStatus() == Order.Status.PAID) {
            return ApiResponse.error("订单已支付，无法取消");
        }
        if (order.getStatus() == Order.Status.CANCELLED) {
            return ApiResponse.success("订单已取消", order);
        }

        LocalDateTime now = LocalDateTime.now();
        Query query = new Query(Criteria.where("_id").is(orderId)
                .and("userId").is(userId)
                .and("status").is(Order.Status.PENDING));
        Update update = new Update()
                .set("status", Order.Status.CANCELLED)
                .set("updatedAt", now);
        com.mongodb.client.result.UpdateResult result = mongoTemplate.updateFirst(query, update, Order.class);
        if (result.getModifiedCount() == 0) {
            Order latest = orderRepository.findById(orderId).orElse(order);
            if (latest.getStatus() == Order.Status.PAID) {
                return ApiResponse.error("订单已支付，无法取消");
            }
            return ApiResponse.success("订单已取消", latest);
        }
        order.setStatus(Order.Status.CANCELLED);
        return ApiResponse.success("订单已取消", order);
    }

    private String generateOrderNo(LocalDateTime now) {
        return now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private String generatePaymentId() {
        return "PAY" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
