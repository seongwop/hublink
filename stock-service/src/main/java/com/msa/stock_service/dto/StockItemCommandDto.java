package com.msa.stock_service.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StockItemCommandDto {
    @JsonAlias("productId")
    private UUID id;
    private Integer quantity;

    private UUID orderId;
    private String orderName;
    private String orderEmail;
    private String deliveryAddress;
    private String receiverCompanyName;
}
