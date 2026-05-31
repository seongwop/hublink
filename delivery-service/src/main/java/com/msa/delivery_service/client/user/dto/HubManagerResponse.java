package com.msa.delivery_service.client.user.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
public class HubManagerResponse {

    private UUID hubManagerId;
    private UUID hubId;
    private String hubManagerSlackId;
}
