package com.msa.product_service.controller;

import com.msa.product_service.dto.ProductResponseDto;
import com.msa.product_service.service.ProductService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/products")
@RequiredArgsConstructor
public class ProductInternalController {

    private final ProductService productService;

    @PostMapping("/byIdList")
    public List<ProductResponseDto> getProductsById(@RequestBody List<UUID> productIdList) {
        return productService.getProductsById(productIdList);
    }
}
