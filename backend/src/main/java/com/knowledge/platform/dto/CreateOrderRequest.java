package com.knowledge.platform.dto;

import lombok.Data;

@Data
public class CreateOrderRequest {
    /**
     * 订单类型：COLUMN_SUBSCRIPTION / AUDIO_PURCHASE / EBOOK_PURCHASE
     */
    private String type;

    /**
     * 商品 ID（专栏 ID、课程 ID 等）
     */
    private String itemId;

    /**
     * 订阅方案（专栏订阅必填）：MONTHLY / QUARTERLY / YEARLY
     */
    private String plan;
}
