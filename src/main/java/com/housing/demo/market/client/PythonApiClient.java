package com.housing.demo.market.client;

import com.housing.demo.market.model.PropertyRecord;
import com.housing.demo.market.model.PythonHistoryResponse;
import com.housing.demo.market.model.WhatIfRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class PythonApiClient {

    private final RestClient restClient;
    private final String pythonApiUrl;

    public PythonApiClient(@Value("${python.api.url:http://localhost:8000}") String pythonApiUrl) {
        this.pythonApiUrl = pythonApiUrl.endsWith("/") ? pythonApiUrl.substring(0, pythonApiUrl.length() - 1) : pythonApiUrl;
        this.restClient = RestClient.builder().build();
    }

    /**
     * Python api: get latest estimated history
     */
    public List<PropertyRecord> fetchLatestHistory() {
        try {
            URI targetUri = URI.create(this.pythonApiUrl + "/api/estimates/history?page=1&page_size=25");

            PythonHistoryResponse response = restClient.get()
                .uri(targetUri)
                .retrieve()
                .body(PythonHistoryResponse.class);

            return response != null && response.records() != null ? response.records() : Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Failed to fetch history from Python API: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * Housing price prediction
     */
    public Map<String, Object> predictWhatIfPrice(WhatIfRequest req) {
        Map<String, Object> modelFeatures = Map.of(
            "square_footage", req.squareFootage(),
            "bedrooms", req.bedrooms(),
            "bathrooms", req.bathrooms(),
            "year_built", req.yearBuilt(),
            "lot_size", req.lotSize(),
            "distance_to_city_center", req.distanceToCityCenter(),
            "school_rating", req.schoolRating()
        );

        return restClient.post()
            .uri("/predict")
            .contentType(MediaType.APPLICATION_JSON)
            .body(modelFeatures)
            .retrieve()
            .body(Map.class);
    }
}