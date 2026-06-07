package com.msa.stock_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.stock_service.client.ProductClient;
import com.msa.stock_service.client.ProductResponse;
import com.msa.stock_service.dto.StockHistoryResponseDto;
import com.msa.stock_service.dto.StockItemCommandDto;
import com.msa.stock_service.entity.StockHistory;
import com.msa.stock_service.global.StockError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class StockOrchestrator {

    private final ProductClient productClient;
    private final StockService stockService;

    /**
     * 재고 감소 -> 재고 이력 생성 이 재고에 해당하는 가격과 이름을 달아 반환한다.
     * @param listDto
     * @return
     */
    @Transactional
    public List<StockHistoryResponseDto> decreaseStock(List<StockItemCommandDto> listDto) {

        Map<UUID, StockItemCommandDto> mapDto = new HashMap<>();
        for (StockItemCommandDto dto : listDto) {
            mapDto.put(dto.getId(), dto);
        }

        // 재고 차감 전 상품 정보 조회
        List<UUID> productIdList = listDto.stream().map(StockItemCommandDto::getId)
            .collect(Collectors.toList());
        List<ProductResponse> productList = getProductsById(productIdList);

        // 상품 정보 조회 성공 후 재고 감소
        List<StockHistory> histories = stockService.decreaseStock(listDto);

        //이 상품 목록을 Map으로 변환한다.
        Map<UUID, ProductResponse> productMap = productList.stream()
            .collect(Collectors.toMap(ProductResponse::getProductId, product -> product));

        //반환할 StockHistoryResponseDto 리스트를 만든다.
        List<StockHistoryResponseDto> stockHistoryResponseDtos = new ArrayList<>();

        //재고 이력목록을 반복해서,
        for (StockHistory history : histories) {
            //그 재고 이력에 해당하는 상품 정보를 가져온다.
            ProductResponse productResponse = productMap.get(history.getProductId());

            //상품이 null인 경우 방어
            if(productResponse == null) {
                throw new CustomException(StockError.PRODUCT_INFO_NOT_FOUND);
            }

            //이 상품 정보들은 전부 성공 여부가 true인 것들이고,
            boolean isSuccess = true;
            //요청 값으로 날린 Order 정보들을 그대로 다시 응답해야하므로, dto 객체 다시 가져옴.
            StockItemCommandDto dto = mapDto.get(history.getProductId());

            //그 dto가 null인 경우에도 다시 방어
            if(dto == null) {
                throw new CustomException(StockError.ORDER_DTO_NOT_MATCHED);
            }

            //이제 반환할 StockHistoryResponseDto(상품 정보가 포함된 이력)를 하나씩 만든다.
            StockHistoryResponseDto result = StockHistoryResponseDto.from(history, isSuccess,
                productResponse.getName(), productResponse.getPrice(),
                dto);

            //만든 StockHistoryResponseDto을 StockHistoryResponseDto 리스트에 저장
            stockHistoryResponseDtos.add(result);
        }
        return stockHistoryResponseDtos;
    }

    private List<ProductResponse> getProductsById(List<UUID> productIdList) {
        try {
            List<ProductResponse> products = productClient.getProductsById(productIdList);
            if (products == null) {
                throw new CustomException(StockError.PRODUCT_INFO_NOT_FOUND);
            }
            return products;
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(StockError.PRODUCT_INFO_NOT_FOUND);
        }
    }
}
