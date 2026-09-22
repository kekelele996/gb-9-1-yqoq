package com.knowledge.platform.dto;

import lombok.Data;

@Data
public class OrderCreateRequest {
    private String type;

    private String itemId;

    private String plan;
}
