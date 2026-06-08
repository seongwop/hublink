package com.msa.delivery_service.repository;

import java.util.UUID;

public interface ManagerAssignmentCount {

    UUID getManagerId();

    long getAssignmentCount();
}
