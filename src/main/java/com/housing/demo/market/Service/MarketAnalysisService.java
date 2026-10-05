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

    // Feature Flag
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

    /**
     * What-If 宏观推演分析：
     * 取 LRU Cache 中最近的最多 8 套房产，基于未来 5 年复合通胀率 (默认 3%) 与贷款利率 (默认 5.5%)
     * 逐年计算价值演进轨迹 (2026-2031) 以及未来的按揭月供
     */
    public Map<String, Object> runWhatIfAnalysis(WhatIfRequest req) {
        // 1. 读取参数或使用默认宏观基准
        int years = (req != null && req.yearsAhead() != null && req.yearsAhead() > 0) ? req.yearsAhead() : 5;
        double inflationRate = (req != null && req.inflationRate() != null) ? req.inflationRate() : 3.0; // 3%
        double mortgageRate = (req != null && req.mortgageRate() != null) ? req.mortgageRate() : 5.5;   // 5.5%
        String scenario = (req != null && req.scenarioName() != null && !req.scenarioName().isBlank())
                            ? req.scenarioName()
                            : "5-Year Macroeconomic Trajectory";

        // 2. Get top 8 most recently estimated housing prices
        List<PropertyRecord> allHistory = getCachedOrFreshHistory();
        if (allHistory.isEmpty()) {
            return Map.of(
                "status", "NO_DATA",
                "message", "No historical properties found in cache. Generate estimates first."
            );
        }

        List<PropertyRecord> top8Properties = allHistory.stream()
            .limit(8)
            .collect(Collectors.toList());

        // 3. 计算第 5 年的总复合通胀膨胀系数: (1 + r)^t
        double cumulativeAppreciationFactor = Math.pow(1.0 + (inflationRate / 100.0), years);

        // 4. 对最多 8 套房产逐个进行未来 0~5 年的逐年轨迹推演
        List<Map<String, Object>> projectedProperties = new ArrayList<>();
        double totalBasePrice = 0.0;
        double totalFuturePrice = 0.0;

        for (PropertyRecord prop : top8Properties) {
            double currentPrice = prop.predictedPrice();

            // 生成逐年价值演进点 (2026, 2027, 2028, 2029, 2030, 2031)
            List<Map<String, Object>> trajectory = new ArrayList<>();
            for (int t = 0; t <= years; t++) {
                int displayYear = 2026 + t; // 基准当前年份为 2026
                double priceAtYearT = Math.round(currentPrice * Math.pow(1.0 + (inflationRate / 100.0), t));
                trajectory.add(Map.of(
                    "year", displayYear,
                    "yearOffset", t,
                    "price", priceAtYearT
                ));
            }

            // 第 5 年 (2031) 的推演总价
            double projectedFinalPrice = (double) trajectory.get(years).get("price");

            // 计算 30 年期等额本息月供 (按首付 20%，贷款 80%，贷款年利率 5.5% 计算)
            double estimatedMonthlyPayment = calculateMonthlyMortgage(projectedFinalPrice * 0.8, mortgageRate, 30);

            totalBasePrice += currentPrice;
            totalFuturePrice += projectedFinalPrice;

            Map<String, Object> item = new HashMap<>();
            item.put("id", prop.id());
            item.put("propertyName", prop.propertyName());
            item.put("currentPrice", currentPrice);
            item.put("projectedPrice", projectedFinalPrice);
            item.put("projectedGain", Math.round(projectedFinalPrice - currentPrice));
            item.put("estimatedMonthlyMortgage", Math.round(estimatedMonthlyPayment));
            item.put("squareFootage", prop.squareFootage());
            item.put("bedrooms", prop.bedrooms());
            item.put("trajectory", trajectory); // 供前端折线图绘制 8 根多点曲线

            projectedProperties.add(item);
        }

        int count = top8Properties.size();
        double avgCurrentPrice = Math.round(totalBasePrice / count);
        double avgFuturePrice = Math.round(totalFuturePrice / count);

        // 5. 组合推演综合响应
        return Map.of(
            "status", "SUCCESS",
            "scenario", scenario,
            "macroAssumptions", Map.of(
                "yearsAhead", years,
                "annualInflationRate", inflationRate + "%",
                "averageMortgageRate", mortgageRate + "%",
                "cumulativeGrowthMultiplier", Math.round(cumulativeAppreciationFactor * 1000.0) / 1000.0
            ),
            "sampleSize", count,
            "summary", Map.of(
                "avgCurrentPrice", avgCurrentPrice,
                "avgProjectedPrice", avgFuturePrice,
                "overallAppreciationPct", Math.round(((avgFuturePrice - avgCurrentPrice) / avgCurrentPrice) * 1000.0) / 10.0 + "%"
            ),
            "projections", projectedProperties
        );
    }

    /**
     * 辅助公式：计算 30 年期等额本息月供 (Standard Fixed-rate Mortgage)
     * M = P * [ i(1 + i)^n ] / [ (1 + i)^n – 1 ]
     */
    private double calculateMonthlyMortgage(double principal, double annualRatePct, int years) {
        if (principal <= 0 || annualRatePct <= 0) return 0.0;
        double monthlyRate = (annualRatePct / 100.0) / 12.0;
        int totalMonths = years * 12;
        double factor = Math.pow(1.0 + monthlyRate, totalMonths);
        return (principal * monthlyRate * factor) / (factor - 1.0);
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