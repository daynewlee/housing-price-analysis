package com.housing.demo.market.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PropertyRecord(
    Long id,
    @JsonProperty("property_name") String propertyName,
    @JsonProperty("square_footage") double squareFootage,
    int bedrooms,
    double bathrooms,
    @JsonProperty("year_built") int yearBuilt,
    @JsonProperty("lot_size") double lotSize,
    @JsonProperty("distance_to_city_center") double distanceToCityCenter,
    @JsonProperty("school_rating") double schoolRating,
    @JsonProperty("predicted_price") double predictedPrice,
    String currency,
    @JsonProperty("created_at") String createdAt
) {}