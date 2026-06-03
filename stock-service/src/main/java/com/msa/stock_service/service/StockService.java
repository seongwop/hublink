package com.msa.stock_service.service;

import com.msa.core_common.error.exception.CustomException;
import com.msa.core_common.response.paging.PageRes;
import com.msa.stock_service.dto.StockHistoryModifyDto;
import com.msa.stock_service.dto.StockHistorySearchResponseDto;
import com.msa.stock_service.dto.StockItemCommandDto;
import com.msa.stock_service.dto.StockRequestDto;
import com.msa.stock_service.entity.Stock;
import com.msa.stock_service.entity.StockHistory;
import com.msa.stock_service.global.StockError;
import com.msa.stock_service.repository.StockHistoryRepository;
import com.msa.stock_service.repository.StockRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockService {
    private final StockRepository stockRepository;
    private final StockHistoryRepository stockHistoryRepository;

    /**
     * 재고 조정
     * @param dto
     * @return
     */
    @Transactional
    public StockHistoryModifyDto modifyStock(StockRequestDto dto){
        //상품아이디로 재고를 조회한다.
        Stock modifyStock = stockRepository.findByProductId(dto.getProductId());

        if(modifyStock == null) {
            throw new IllegalArgumentException("조회된 재고 없음");
        }
        //전달받은 허브 아이디와 조회한 재고의 허브 아이디를 비교하여 둘이 같은지 판단한다.
        if(!dto.getHubId().equals(modifyStock.getHubId())) {
            throw new IllegalArgumentException("해당 허브에 관리되는 재고가 아님.");
        }

        //조회된 재고에서 수량을 미리 뺀다.
        Integer beforeQuantity = modifyStock.getQuantity();

        //재고를 변경한다.
        modifyStock.modifyQuantity(dto.getQuantity());

        //이에 대한 재고 이력을 생성한다.
        StockHistory newHistory = StockHistory.adjust(modifyStock, beforeQuantity);

        //재고를 반환한다.
        return StockHistoryModifyDto.from(newHistory);
    }

    /**
     * 재고 목록 조회
     * @param productId
     * @param pageable
     * @return
     */
    @Transactional(readOnly = true)
    public PageRes<StockHistorySearchResponseDto> getStockhistories(UUID productId, Pageable pageable) {
        Page<StockHistory> stockHistoryPage = stockHistoryRepository.searchHistoriesByproductId(productId, pageable);

        return new PageRes<>(stockHistoryPage.map(StockHistorySearchResponseDto::from));
    }

    /**
     * 재고 복원
     * @param listDto
     */
    @Transactional
    public void restoreStock(List<StockItemCommandDto> listDto){
        //전달 받은 데이터 리스트를 map으로 변환한다.
        // 한번 요청에 동일한 productId가 올 수 있으므로, 아래와 같이 수량을 합산.
        Map<UUID,Integer> restoreQuantityMap = listDto.stream().collect(Collectors.toMap(
            StockItemCommandDto :: getId,
            StockItemCommandDto :: getQuantity,
            Integer::sum
        ));

        //Map에서 productID 꺼내기
        List<UUID> productIdList = new ArrayList<>(restoreQuantityMap.keySet());

        //복원된 재고 이력 DB에 저장하기 위해 새로운 재고 이력 리스트 생성.
        List<StockHistory> restoreStockHistory = new ArrayList<>();

        //비관적으로 복원할 수량을 가져온다.
        List<Stock> stockList = stockRepository.findAllByProductIdInForUpdate(productIdList);

        for (Stock stock : stockList) {
            //전달받은 데이터에서 수량 가져오기
            Integer restoreQuantity = restoreQuantityMap.getOrDefault(stock.getProductId(), 0);

            // 그 수량이 0인 경우에만
            if (restoreQuantity > 0) {
                Integer beforeQuantity = stock.getQuantity();
                // 재고 복원
                stock.restore(restoreQuantity);

                // 복원된 재고의 이력을 만든다.
                StockHistory newHistory = StockHistory.restore(stock, restoreQuantity, beforeQuantity);
                // 복원된 재고의 이력만 리스트를 만들어
                restoreStockHistory.add(newHistory);
            }
        }
        // DB에 저장한다.
        stockHistoryRepository.saveAll(restoreStockHistory);

    }


    /**
     * 재고 감소 기능
     * 재고 조회 -> 주문 수량과 재고 수량 비교 -> 재고 감소 -> 이에 따른 재고 이력 생성
     * @param listDto
     * @return
     */
    @Transactional
    public List<StockHistory> decreaseStock (List<StockItemCommandDto> listDto){
       //listDto에서는 id와 quantity만 사용하므로 이 둘의 값을 가진 Map을 만든다.
        Map<UUID, Integer> requestQuantityMap = listDto.stream()
            .collect(Collectors.toMap(
                StockItemCommandDto::getId,
                StockItemCommandDto::getQuantity,
                Integer::sum
            ));

        //변환된 Map에서 상품 id를 추출한다.
        List<UUID> productIdList = new ArrayList<>(requestQuantityMap.keySet());

        //상품 ID와 비관적 락을 사용하여, 각 상품별 재고 리스트를 가져온다.
        List<Stock> stockList = stockRepository.findAllByProductIdInForUpdate(productIdList);

        //조회된 이 재고 리스트와, 전달 받은 데이터의 수가 동일해야지, 재고 차감을 진행할 수 있다.
        if (stockList.size() != requestQuantityMap.size()) {
            //만일 동일하지 않다면, 일부 재고가 잘못 등록된 것으로 간주하여, 에러를 내보낸다.
            throw new CustomException(StockError.STOCK_NOT_FOUND);
        }

        //DB에 등록할 재고 이력 리스트를 새롭게 생성
        List<StockHistory> newStockHistory = new ArrayList<>();

        //재고 리스트를 순회하며
        for (Stock stock : stockList) {
            //전달받은 데이터 중 주문 수량을 꺼내서
            Integer requestQuantity = requestQuantityMap.get(stock.getProductId());

            //현재 재고의 수량이 주문한 수량보다 더 크다면
            if (stock.getQuantity() >= requestQuantity) {
                Integer beforeQuantity = stock.getQuantity();
                // 정상 차감 및 이력 생성.
                stock.decreaseStock(requestQuantity);
                newStockHistory.add(StockHistory.createDecreaseStock(stock, requestQuantity, beforeQuantity));
            } else {
                // 그 외로 재고가 부족하다면, 에러를 일으킨다.
                throw new IllegalArgumentException("재고가 부족합니다. 상품 ID: " + stock.getProductId());
            }
        }

        // 생성된 재고 이력을 DB에 모두 저장시킨다.
        stockHistoryRepository.saveAll(newStockHistory);

        return newStockHistory;
    }

    /**
     * 재고 생성 및 재고 이력을 생성
     * @param dto
     * @return
     */
    @Transactional
    public Stock createStock(StockRequestDto dto){
        // 재고를 DB에 생성한다.
        Stock newStock = Stock.create(dto.getProductId(),dto.getHubId(),dto.getQuantity());
        stockRepository.save(newStock);

        // 재고 이력을 DB에 생성한다.
        StockHistory newStockHistory = StockHistory.create(newStock);
        stockHistoryRepository.save(newStockHistory);

        return newStock;
    }

}
