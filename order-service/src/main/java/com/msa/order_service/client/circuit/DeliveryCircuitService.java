package com.msa.order_service.client.circuit;

import com.msa.core_common.error.exception.CustomException;
import com.msa.order_service.client.DeliveryFeignClient;
import com.msa.order_service.dto.req.MakeDeliveryReqDto;
import com.msa.order_service.dto.res.MakeDeliveryResDto;
import com.msa.order_service.error.OrderErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryCircuitService {

    private final DeliveryFeignClient deliveryFeignClient;

    @CircuitBreaker(name = "makeDelivery", fallbackMethod = "makeDeliveryFallback")
    public MakeDeliveryResDto makeDelivery(@RequestBody MakeDeliveryReqDto makeDeliveryReqDto) {
        return deliveryFeignClient.makeDelivery(makeDeliveryReqDto);
    }

    public MakeDeliveryResDto makeDeliveryFallback(MakeDeliveryReqDto makeDeliveryReqDto, Throwable t) {
        log.error("event=ORDER_DELIVERY_FALLBACK orderId={} reason={}", makeDeliveryReqDto.getOrderId(), t.getMessage());
        throw new CustomException(OrderErrorCode.FAIL_DELIVERY);
    }
}
