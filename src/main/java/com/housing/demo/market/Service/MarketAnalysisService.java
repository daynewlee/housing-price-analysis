package com.housing.demo.market.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.housing.demo.market.cache.LruCache;
import com.housing.demo.market.client.PythonApiClient;
import com.housing.demo.market.model.AggregateResult;
import com.housing.demo.market.model.PropertyRecord;
import com.housing.demo.market.model.WhatIfRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MarketAnalysisService {

    private static final String HISTORY_CACHE_KEY = "CACHED_HISTORY_DATA";

    private final PythonApiClient pythonApiClient;
    private final LruCache<String, List<PropertyRecord>> lruCache;
    private final ObjectMapper objectMapper;

    // Feature Flag 注入，可通过环境变量 APP_MOCK_MODE=true 覆盖
    @Value("${app.mock-mode:false}")
    private boolean mockMode;

    public MarketAnalysisService(PythonApiClient pythonApiClient, 
                                 LruCache<String, List<PropertyRecord>> lruCache,
                                 ObjectMapper objectMapper) {
        this.pythonApiClient = pythonApiClient;
        this.lruCache = lruCache;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetch data:
     * 1. Check LRU cache
     * 2. LRU cache is outdated：mock_properties.json is read if mock mode is on；otherwise it will reach out to Python API
     * 3. Update LRU cache
     */
    public List<PropertyRecord> getCachedOrFreshHistory() {
        if (lruCache.containsKey(HISTORY_CACHE_KEY)) {
            System.out.println("[CACHE HIT] Fetching properties directly from LRU Cache.");
            return lruCache.get(HISTORY_CACHE_KEY);
        }

        System.out.println("[CACHE MISS] Loading data from upstream/mock source...");
        List<PropertyRecord> data;

        if (mockMode) {
            System.out.println("[FEATURE FLAG ENABLED] Loading mock properties from mock_properties.json");
            data = loadMockProperties();
        } else {
            data = pythonApiClient.fetchLatestHistory();
        }

        if (!data.isEmpty()) {
            lruCache.put(HISTORY_CACHE_KEY, data);
            System.out.println("[CACHE STORE] Stored " + data.size() + " records into LRU Cache.");
        }
        return data;
    }

    private List<PropertyRecord> loadMockProperties() {
        try {
            ClassPathResource resource = new ClassPathResource("mock_properties.json");
            try (InputStream is = resource.getInputStream()) {
                return objectMapper.readValue(is, new TypeReference<List<PropertyRecord>>() {});
            }
        } catch (Exception e) {
            System.err.println("Error reading mock_properties.json: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public Map<String, Object> runWhatIfAnalysis(WhatIfRequest req) {
        Map<String, Object> inference;

        if (mockMode) { // mock mode
            double simulatedPrice = req.squareFootage() * 200 + (req.yearBuilt() - 2000) * 1500;
            inference = Map.of(
                "predicted_price", Math.round(simulatedPrice),
                "currency", "USD",
                "simulated", true
            );
        } else {
            inference = pythonApiClient.predictWhatIfPrice(req);
        }

        List<PropertyRecord> data = getCachedOrFreshHistory();
        double currentMarketAvg = data.stream()
            .mapToDouble(PropertyRecord::predictedPrice)
            .average()
            .orElse(0.0);

        return Map.of(
            "scenario", req.scenarioName() != null ? req.scenarioName() : "Future Projection",
            "projectedYear", req.yearBuilt(),
            "modelInference", inference,
            "marketBaselineAverage", Math.round(currentMarketAvg)
        );
    }

    public List<PropertyRecord> filterAndSort(Double minPrice, Double maxPrice, Integer minBedrooms, String sortBy) {
        List<PropertyRecord> data = getCachedOrFreshHistory();
        return data.stream()
            .filter(p -> minPrice == null || p.predictedPrice() >= minPrice)
            .filter(p -> maxPrice == null || p.predictedPrice() <= maxPrice)
            .filter(p -> minBedrooms == null || p.bedrooms() >= minBedrooms)
            .sorted((a, b) -> {
                if ("price_desc".equalsIgnoreCase(sortBy)) return Double.compare(b.predictedPrice(), a.predictedPrice());
                if ("year".equalsIgnoreCase(sortBy)) return Integer.compare(b.yearBuilt(), a.yearBuilt());
                if ("distance".equalsIgnoreCase(sortBy)) return Double.compare(a.distanceToCityCenter(), b.distanceToCityCenter());
                return Double.compare(a.predictedPrice(), b.predictedPrice());
            })
            .collect(Collectors.toList());
    }

    public Map<String, Object> aggregateByDistance() {
        List<PropertyRecord> data = getCachedOrFreshHistory();
        if (data.isEmpty()) {
            return Map.of("status", "NO_DATA", "message", "No historical data available. Please generate estimates first.");
        }

        Map<String, List<PropertyRecord>> grouped = data.stream().collect(Collectors.groupingBy(p -> {
            double d = p.distanceToCityCenter();
            if (d < 15.0) return "0 - 15 miles";
            if (d < 35.0) return "15 - 35 miles";
            if (d < 55.0) return "35 - 55 miles";
            return "55+ miles";
        }));

        return Map.of(
            "status", "SUCCESS",
            "metric", "Distance to City Center",
            "aggregates", calculateBucketStats(grouped)
        );
    }

    public Map<String, Object> aggregateByYear() {
        List<PropertyRecord> data = getCachedOrFreshHistory();
        if (data.isEmpty()) {
            return Map.of("status", "NO_DATA", "message", "No historical data available. Please generate estimates first.");
        }

        Map<String, List<PropertyRecord>> grouped = data.stream().collect(Collectors.groupingBy(p -> {
            int y = p.yearBuilt();
            if (y >= 2006) return "2006 - 2026";
            if (y >= 1986) return "1986 - 2006";
            return "Older than 1986";
        }));

        return Map.of(
            "status", "SUCCESS",
            "metric", "Construction Era",
            "aggregates", calculateBucketStats(grouped)
        );
    }

    public void invalidateCache() {
        lruCache.clear();
        System.out.println("[CACHE EVICTED] LRU Cache has been wiped.");
    }

    private List<AggregateResult> calculateBucketStats(Map<String, List<PropertyRecord>> grouped) {
        List<AggregateResult> results = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<PropertyRecord> list = entry.getValue();
            long count = list.size();
            double avg = list.stream().mapToDouble(PropertyRecord::predictedPrice).average().orElse(0.0);
            double min = list.stream().mapToDouble(PropertyRecord::predictedPrice).min().orElse(0.0);
            double max = list.stream().mapToDouble(PropertyRecord::predictedPrice).max().orElse(0.0);
            results.add(new AggregateResult(entry.getKey(), count, Math.round(avg), min, max));
        }
        return results;
    }
}