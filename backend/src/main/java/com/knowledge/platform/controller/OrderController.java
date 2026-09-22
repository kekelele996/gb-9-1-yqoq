package com.knowledge.platform.controller;

import com.knowledge.platform.dto.ApiResponse;
import com.knowledge.platform.dto.OrderCreateRequest;
import com.knowledge.platform.entity.Order;
import com.knowledge.platform.security.CurrentUserUtil;
import com.knowledge.platform.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {
    @Autowired
    private OrderService orderService;

    @Autowired
    private CurrentUserUtil currentUserUtil;

    @GetMapping
    public ApiResponse<Page<Order>> myOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderService.getMyOrders(userId, pageable);
    }

    @GetMapping("/{id}")
    public ApiResponse<Order> getById(@PathVariable String id) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        return orderService.getMyOrder(userId, id);
    }

    /** 选择月付/季付/年付后生成待支付订单 */
    @PostMapping
    public ApiResponse<Order> create(@RequestBody OrderCreateRequest request) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        return orderService.createOrder(userId, request);
    }

    /** 支付订单：支付成功后订单转为已支付并开通/续接订阅 */
    @PostMapping("/{id}/pay")
    public ApiResponse<Order> pay(@PathVariable String id) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        return orderService.payOrder(userId, id);
    }

    /** 取消待支付订单 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Order> cancel(@PathVariable String id) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        return orderService.cancelOrder(userId, id);
    }
}
