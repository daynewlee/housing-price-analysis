package com.housing.demo.market.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WhatIfRequest(
    @JsonProperty("scenario_name") String scenarioName,
    @JsonProperty("square_footage") double squareFootage,
    int bedrooms,
    double bathrooms,
    @JsonProperty("year_built") int yearBuilt, // 校验 >= 2026
    @JsonProperty("lot_size") double lotSize,
    @JsonProperty("distance_to_city_center") double distanceToCityCenter,
    @JsonProperty("school_rating") double schoolRating
) {}