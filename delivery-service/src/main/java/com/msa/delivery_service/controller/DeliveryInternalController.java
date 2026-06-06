package com.msa.delivery_service.controller;

import com.msa.delivery_service.service.DeliveryService;
import com.msa.delivery_service.dto.DeliveryRequest;
import com.msa.delivery_service.dto.DeliveryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/deliveries")
@Tag(name = "Internal Delivery", description = "배송 외부 호출 API")
public class DeliveryInternalController {

    private final DeliveryService deliveryService;

    @Operation(summary = "배송 생성")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryResponse createDelivery(@Valid @RequestBody DeliveryRequest request) {
        return deliveryService.createDelivery(request);
    }

    @Operation(summary = "배송 생성 - 보상 API")
    @PostMapping("/orders/{orderId}/compensate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void compensateDeliveryCreation(@PathVariable UUID orderId) {
        deliveryService.compensateDeliveryCreation(orderId);
    }
}
