package com.msa.stock_service.dto;

import com.msa.stock_service.entity.Stock;
import com.msa.stock_service.entity.StockChangeReason;
import com.msa.stock_service.entity.StockHistory;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHistoryResponseDto {
    private UUID productId;
    private UUID stockId;
    private UUID hubId;
    private Integer changeQuantity;
    private StockChangeReason reason;
    private boolean isSuccess;
    private String name;
    private Integer price;

    private UUID orderId;
    private String orderName;
    private String orderEmail;
    private String deliveryAddress;
    private String receiverCompanyName;

    public static StockHistoryResponseDto from(StockHistory stockHistory, boolean isSuccess, String name, Integer price, StockItemCommandDto listDto) {
        return StockHistoryResponseDto.builder().
            productId(stockHistory.getProductId()).
            stockId(stockHistory.getId()).
            hubId(stockHistory.getHubId()).
            reason(stockHistory.getReason()).
            changeQuantity(stockHistory.getChangeQuantity()).
            isSuccess(isSuccess).
            name(name).
            price(price).
            orderId(listDto.getOrderId()).
            orderName(listDto.getOrderName()).
            deliveryAddress(listDto.getDeliveryAddress()).
            receiverCompanyName(listDto.getReceiverCompanyName()).
            orderEmail(listDto.getOrderEmail()).
            build();
    }
}
