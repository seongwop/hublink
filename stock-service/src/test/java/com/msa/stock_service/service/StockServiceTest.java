package com.msa.stock_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.response.paging.PageRes;
import com.msa.stock_service.dto.StockItemCommandDto;
import com.msa.stock_service.dto.StockHistoryModifyDto;
import com.msa.stock_service.dto.StockHistorySearchResponseDto;
import com.msa.stock_service.dto.StockRequestDto;
import com.msa.stock_service.entity.Stock;
import com.msa.stock_service.entity.StockHistory;
import com.msa.stock_service.repository.StockHistoryRepository;
import com.msa.stock_service.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockHistoryRepository stockHistoryRepository;

    @InjectMocks
    private StockService stockService;

    private UUID productId;
    private UUID hubId;

    @BeforeEach
    void setUp() {
        productId = UUID.randomUUID();
        hubId = UUID.randomUUID();
    }

    // ===================== createStock =====================

    @Test
    @DisplayName("createStock - 정상적으로 재고와 재고 이력을 생성한다")
    void createStock_Success() {
        // given
        StockRequestDto dto = new StockRequestDto(productId, hubId, 100, null);

        given(stockRepository.save(any(Stock.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
        given(stockHistoryRepository.save(any(StockHistory.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        // when
        Stock result = stockService.createStock(dto);

        // then
        assertThat(result.getProductId()).isEqualTo(productId);
        assertThat(result.getHubId()).isEqualTo(hubId);
        assertThat(result.getQuantity()).isEqualTo(100);

        verify(stockRepository, times(1)).save(any(Stock.class));
        verify(stockHistoryRepository, times(1)).save(any(StockHistory.class));
    }

    // ===================== modifyStock =====================

    @Test
    @DisplayName("modifyStock - 정상적으로 재고 수량을 조정하고 이력을 반환한다")
    void modifyStock_Success() {
        // given
        Stock existingStock = Stock.builder()
            .productId(productId)
            .hubId(hubId)
            .quantity(50)
            .reservedQuantity(0)
            .build();

        StockRequestDto dto = new StockRequestDto(productId, hubId, 80, null);
        given(stockRepository.findByProductId(productId)).willReturn(existingStock);

        // when
        StockHistoryModifyDto result = stockService.modifyStock(dto);

        // then
        assertThat(result).isNotNull();
        assertThat(existingStock.getQuantity()).isEqualTo(80);
    }

    @Test
    @DisplayName("modifyStock - 재고가 존재하지 않으면 IllegalArgumentException 발생")
    void modifyStock_StockNotFound() {
        // given
        StockRequestDto dto = new StockRequestDto(productId, hubId, 80, null);
        given(stockRepository.findByProductId(productId)).willReturn(null);

        // when & then
        assertThatThrownBy(() -> stockService.modifyStock(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("조회된 재고 없음");
    }

    @Test
    @DisplayName("modifyStock - 허브 ID가 불일치하면 IllegalArgumentException 발생")
    void modifyStock_HubMismatch() {
        // given
        UUID differentHubId = UUID.randomUUID();
        Stock existingStock = Stock.builder()
            .productId(productId)
            .hubId(hubId)
            .quantity(50)
            .reservedQuantity(0)
            .build();

        StockRequestDto dto = new StockRequestDto(productId, differentHubId, 80, null);
        given(stockRepository.findByProductId(productId)).willReturn(existingStock);

        // when & then
        assertThatThrownBy(() -> stockService.modifyStock(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("해당 허브에 관리되는 재고가 아님.");
    }

    // ===================== getStockhistories =====================

    @Test
    @DisplayName("getStockhistories - productId로 재고 이력 페이지를 반환한다")
    void getStockhistories_Success() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // ✅ 변경된 반환 타입 Page<StockHistory> 에 맞게 수정
        Page<StockHistory> mockPage = new PageImpl<>(List.of(), pageable, 0);

        given(stockHistoryRepository.searchHistoriesByproductId(productId, pageable))
            .willReturn(mockPage);

        // when
        PageRes<StockHistorySearchResponseDto> result = stockService.getStockhistories(productId, pageable);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        verify(stockHistoryRepository, times(1)).searchHistoriesByproductId(productId, pageable);
    }

    // ===================== decreaseStock =====================

    @Test
    @DisplayName("decreaseStock - 재고가 충분하면 정상적으로 감소하고 이력을 반환한다")
    void decreaseStock_Success() {
        // given
        UUID productId2 = UUID.randomUUID();

        Stock stock1 = Stock.builder().productId(productId).hubId(hubId).quantity(100).reservedQuantity(0).build();
        Stock stock2 = Stock.builder().productId(productId2).hubId(hubId).quantity(50).reservedQuantity(0).build();

        // 수정 1: 7개 필드 생성자 사용 (불필요한 값은 null)
        List<StockItemCommandDto> request = List.of(
            new StockItemCommandDto(productId,  30, null, null, null, null, null),
            new StockItemCommandDto(productId2, 20, null, null, null, null, null)
        );

        given(stockRepository.findAllByProductIdInForUpdate(anyList()))
            .willReturn(List.of(stock1, stock2));
        given(stockHistoryRepository.saveAll(anyList()))
            .willAnswer(invocation -> invocation.getArgument(0));

        // when
        List<StockHistory> result = stockService.decreaseStock(request);

        // then
        assertThat(result).hasSize(2);
        assertThat(stock1.getQuantity()).isEqualTo(70);  // 100 - 30
        assertThat(stock2.getQuantity()).isEqualTo(30);  // 50  - 20
        verify(stockHistoryRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("decreaseStock - 재고가 부족하면 IllegalArgumentException 발생")
    void decreaseStock_InsufficientStock() {
        // given
        Stock stock = Stock.builder()
            .productId(productId)
            .hubId(hubId)
            .quantity(10)        // 재고 10개뿐
            .reservedQuantity(0)
            .build();

        // 수정 1: 7개 필드 생성자 사용
        List<StockItemCommandDto> request = List.of(
            new StockItemCommandDto(productId, 50, null, null, null, null, null) // 50개 요청
        );

        given(stockRepository.findAllByProductIdInForUpdate(anyList()))
            .willReturn(List.of(stock));

        // when & then
        assertThatThrownBy(() -> stockService.decreaseStock(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("재고가 부족합니다");
    }

    @Test
    @DisplayName("decreaseStock - 요청한 상품의 재고가 DB에 없으면 CustomException 발생")
    void decreaseStock_StockNotFound() {
        // given
        // 수정 1: 7개 필드 생성자 사용
        List<StockItemCommandDto> request = List.of(
            new StockItemCommandDto(productId,              10, null, null, null, null, null),
            new StockItemCommandDto(UUID.randomUUID(), 10, null, null, null, null, null)
        );

        // 2개 요청했는데 1개만 조회됨
        given(stockRepository.findAllByProductIdInForUpdate(anyList()))
            .willReturn(List.of(
                Stock.builder().productId(productId).quantity(100).reservedQuantity(0).build()
            ));

        // when & then
        assertThatThrownBy(() -> stockService.decreaseStock(request))
            .isInstanceOf(CustomException.class);
    }

    // ===================== restoreStock =====================

    @Test
    @DisplayName("restoreStock - 정상적으로 재고를 복원하고 이력을 저장한다")
    void restoreStock_Success() {
        // given
        Stock stock = Stock.builder()
            .productId(productId)
            .hubId(hubId)
            .quantity(70)
            .reservedQuantity(30)
            .build();

        // 수정 1: 7개 필드 생성자 사용
        List<StockItemCommandDto> request = List.of(
            new StockItemCommandDto(productId, 30, null, null, null, null, null)
        );

        given(stockRepository.findAllByProductIdInForUpdate(anyList()))
            .willReturn(List.of(stock));
        given(stockHistoryRepository.saveAll(anyList()))
            .willAnswer(invocation -> invocation.getArgument(0));

        // when
        stockService.restoreStock(request);

        // then
        assertThat(stock.getQuantity()).isEqualTo(100);       // 70 + 30
        assertThat(stock.getReservedQuantity()).isEqualTo(0);  // 30 - 30
        verify(stockHistoryRepository, times(1)).saveAll(anyList());
    }
}
