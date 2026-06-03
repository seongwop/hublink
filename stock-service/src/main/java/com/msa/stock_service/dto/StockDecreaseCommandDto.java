package com.msa.stock_service.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class StockDecreaseCommandDto {
    private UUID orderId;
    private String ordererName;
    private String ordererEmail;
    private String deliveryAddress;
    private String receiverCompanyName;
    private List<Item> items;

    @Getter
    @NoArgsConstructor
    public static class Item {
        @JsonAlias("id")
        private UUID productId;
        private Integer quantity;
    }
}
