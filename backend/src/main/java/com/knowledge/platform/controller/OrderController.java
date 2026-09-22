package com.knowledge.platform.controller;

import com.knowledge.platform.dto.ApiResponse;
import com.knowledge.platform.dto.CreateOrderRequest;
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
    public ApiResponse<Page<Order>> list(
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
        return orderService.getOrder(userId, id);
    }

    @PostMapping
    public ApiResponse<Order> create(@RequestBody CreateOrderRequest request) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        return orderService.createOrder(userId, request);
    }

    @PostMapping("/{id}/pay")
    public ApiResponse<Order> pay(@PathVariable String id) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        return orderService.pay(userId, id);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Order> cancel(@PathVariable String id) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        return orderService.cancel(userId, id);
    }

    @PostMapping("/{id}/invoice")
    public ApiResponse<String> requestInvoice(@PathVariable String id) {
        String userId = currentUserUtil.getCurrentUserId();
        if (userId == null) {
            return ApiResponse.error("请先登录");
        }
        return orderService.requestInvoice(userId, id);
    }
}
