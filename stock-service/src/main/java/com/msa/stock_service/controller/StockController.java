package com.msa.stock_service.controller;

import com.msa.core_common.response.paging.PageRes;
import com.msa.stock_service.dto.StockHistoryModifyDto;
import com.msa.stock_service.dto.StockHistoryResponseDto;
import com.msa.stock_service.dto.StockHistorySearchResponseDto;
import com.msa.stock_service.dto.StockItemCommandDto;
import com.msa.stock_service.dto.StockRequestDto;
import com.msa.stock_service.dto.StockResponseDto;
import com.msa.stock_service.entity.Stock;
import com.msa.stock_service.service.StockOrchestrator;
import com.msa.stock_service.service.StockService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/stocks")
public class StockController {
    private final StockService stockService;
    private final StockOrchestrator stockOrchestrator;
    /**
     * 특정 상품에 대한 재고
     * @param dto
     * @return
     */
    @PatchMapping("/modify")
    public StockHistoryModifyDto modifyStock(@Valid @RequestBody StockRequestDto dto){
       return  stockService.modifyStock(dto);
    }

    /**
     * 상품에 대한 재고 이력을 조회
     * @param productId
     * @param pageable
     * @return
     */
    @GetMapping("/history")
    public PageRes<StockHistorySearchResponseDto> getStocks(@RequestParam(value = "productId",required = true)UUID productId,
                                                            @PageableDefault(size = 10,page = 0,sort = "createdAt",
                                                                direction = Direction.DESC)Pageable pageable){
        return stockService.getStockhistories(productId, pageable);
    }

    /**
     * 재고 생성
     * @param dto
     * @return
     */
    @PostMapping
    public StockResponseDto createStock(@RequestBody StockRequestDto dto){
        Stock newStock = stockService.createStock(dto);
        return StockResponseDto.from(newStock);
    }

    /**
     * 재고 조회 -> 재고 변경 -> 새로운 재고 이력 생성
     * @param listDto
     * @return
     */
    @PostMapping("/decrease")
    public List<StockHistoryResponseDto> decreaseStock(List<StockItemCommandDto> listDto){
        return stockOrchestrator.decreaseStock(listDto);
    }
}
