package com.msa.stock_service.entity;

import com.msa.core_common.JpaAuditing.baseEntity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "p_stock_histories")
public class StockHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "stock_id", nullable = false)
    private UUID stockId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "hub_id", nullable = false)
    private UUID hubId;

    @Column(name = "change_quantity", nullable = false)
    private Integer changeQuantity; //변경할 수량

    @Builder.Default
    @Column(name = "before_quantity", nullable = false)
    private Integer beforeQuantity = 0; //변경 전 수량

    @Column(name = "after_quantity", nullable = false)
    private Integer afterQuantity; //변경 후 수량

    @Builder.Default
    @Column(nullable = false)
    private StockChangeReason reason = StockChangeReason.PRODUCT_CREATED;

    @Column(name = "reference_id")
    private UUID referenceId;

    //상품 생성 시, 재고 이력 생성.
    public static StockHistory create(Stock stock) {
        return StockHistory.builder()
            .stockId(stock.getId())
            .productId(stock.getProductId())
            .hubId(stock.getHubId())
            .changeQuantity(stock.getQuantity())
            .afterQuantity(stock.getQuantity())
            .build();
    }

    //재고 감소시, 재고 이력 생성
    public static StockHistory createDecreaseStock(Stock stock, Integer orderQuantity, Integer beforeQuantity) {
        return StockHistory.builder().
            stockId(stock.getId()).
            productId(stock.getProductId()).
            hubId(stock.getHubId()).
            changeQuantity(orderQuantity).
            beforeQuantity(beforeQuantity).
            afterQuantity(stock.getQuantity()).
            reason(StockChangeReason.ORDER_CREATED).
            build();
    }

    // 재고 감소 실패 시, 재고 이력 생성.
    public static StockHistory createOutOfStock(Stock stock, Integer orderQuantity) {
        return StockHistory.builder().
            stockId(stock.getId()).
            productId(stock.getProductId()).
            hubId(stock.getHubId()).
            changeQuantity(orderQuantity).
            beforeQuantity(stock.getQuantity()).
            reason(StockChangeReason.OUT_OF_STOCK).
            build();
    }

    public static StockHistory restore(Stock stock, Integer orderQuantity, Integer beforeQuantity) {
        return StockHistory.builder().
            stockId(stock.getId()).
            productId(stock.getProductId()).
            hubId(stock.getHubId()).
            changeQuantity(orderQuantity).
            beforeQuantity(beforeQuantity).
            afterQuantity(stock.getQuantity()).
            reason(StockChangeReason.ORDER_CANCELED).
            build();
    }
    public static StockHistory adjust(Stock stock, Integer beforeQuantity){
        Integer afterQuantity = stock.getQuantity(); // 변경된 최종 수량
        return StockHistory.builder()
            .stockId(stock.getId())
            .productId(stock.getProductId())
            .hubId(stock.getHubId())
            .reason(StockChangeReason.ADJUSTED)
            .changeQuantity(afterQuantity - beforeQuantity)
            .beforeQuantity(beforeQuantity)
            .afterQuantity(afterQuantity)
            .build();

    }
}
