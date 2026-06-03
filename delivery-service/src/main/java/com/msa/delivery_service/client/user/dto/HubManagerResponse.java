package com.msa.delivery_service.client.user.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
public class HubManagerResponse {

    @JsonAlias("userId")
    private UUID hubManagerId;
    private UUID hubId;
    private String hubManagerSlackId;
}
