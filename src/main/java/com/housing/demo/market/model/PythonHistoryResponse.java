package com.housing.demo.market.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PythonHistoryResponse(
    @JsonProperty("total_records") int totalRecords,
    int page,
    @JsonProperty("page_size") int pageSize,
    @JsonProperty("total_pages") int totalPages,
    List<PropertyRecord> records
) {}