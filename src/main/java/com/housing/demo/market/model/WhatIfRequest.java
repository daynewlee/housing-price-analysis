package com.housing.demo.market.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhatIfRequest(
    @JsonProperty("scenario_name") String scenarioName,
    @JsonProperty("years_ahead") Integer yearsAhead,         // 默认 5 年
    @JsonProperty("inflation_rate") Double inflationRate,   // 默认 3.0 (%)
    @JsonProperty("mortgage_rate") Double mortgageRate      // 默认 5.5 (%)
) {}