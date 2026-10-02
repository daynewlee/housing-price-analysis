package com.housing.demo.market.model;

public record AggregateResult(
    String bucket,
    long count,
    double averagePrice,
    double minPrice,
    double maxPrice
) {}