package com.msa.stock_service.client;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "product-service", path = "/internal/products")
public interface ProductClient {
    @PostMapping("/byIdList")
    List<ProductResponse> getProductsById(@RequestBody List<UUID> productIdList);
}
