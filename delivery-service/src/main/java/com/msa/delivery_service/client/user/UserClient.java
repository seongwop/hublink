package com.msa.delivery_service.client.user;

import com.msa.delivery_service.client.user.dto.DeliveryManagerResponse;
import com.msa.delivery_service.client.user.dto.HubManagerResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "user-service", path = "/internal")
public interface UserClient {

    @GetMapping("/hubs/{hubId}")
    HubManagerResponse getHubManager(@PathVariable UUID hubId);

    @PostMapping("/hubs/delivery-managers/search")
    List<DeliveryManagerResponse> getDeliveryManagers(@RequestBody List<UUID> hubIds);
}
