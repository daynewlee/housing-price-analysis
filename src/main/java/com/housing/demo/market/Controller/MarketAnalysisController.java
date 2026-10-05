package com.housing.demo.market.Controller;

import com.housing.demo.market.model.PropertyRecord;
import com.housing.demo.market.model.WhatIfRequest;
import com.housing.demo.market.Service.MarketAnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@CrossOrigin(origins = "*")
public class MarketAnalysisController {

    private final MarketAnalysisService analysisService;

    public MarketAnalysisController(MarketAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /**
     * 1. Filtering and sorting ( minPrice, maxPrice, minBedrooms, sortBy）
     */
    @GetMapping("/properties")
    public ResponseEntity<List<PropertyRecord>> getFilteredProperties(
        @RequestParam(required = false) Double minPrice,
        @RequestParam(required = false) Double maxPrice,
        @RequestParam(required = false) Integer minBedrooms,
        @RequestParam(defaultValue = "price") String sortBy
    ) {
        return ResponseEntity.ok(analysisService.filterAndSort(minPrice, maxPrice, minBedrooms, sortBy));
    }

    /**
     * 2. Aggregate by distance to DT (-15, 15-35, 35-55, 55+ miles）
     */
    @GetMapping("/aggregate/distance")
    public ResponseEntity<Map<String, Object>> getDistanceAggregates() {
        return ResponseEntity.ok(analysisService.aggregateByDistance());
    }

    /**
     * 3. Aggregate by year（2006-2026, 1986-2006, <1986）
     */
    @GetMapping("/aggregate/year")
    public ResponseEntity<Map<String, Object>> getYearAggregates() {
        return ResponseEntity.ok(analysisService.aggregateByYear());
    }

    /**
     * 4. What-If 宏观推演分析接口（未来 5 年，3% 通胀，5.5% 利率，针对 Cache 最近 8 套房）
     */
    @PostMapping("/what-if")
    public ResponseEntity<?> analyzeWhatIf(@RequestBody(required = false) WhatIfRequest request) {
        try {
            // 如果请求体为空，传入空的 DTO 自动取默认值
            WhatIfRequest safeReq = (request != null) ? request : new WhatIfRequest(null, 5, 3.0, 5.5);
            return ResponseEntity.ok(analysisService.runWhatIfAnalysis(safeReq));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to compute what-if macro scenario: " + e.getMessage()));
        }
    }

    /**
     * 5. Refresh cache
     */
    @PostMapping("/cache/refresh")
    public ResponseEntity<Map<String, String>> refreshCache() {
        analysisService.invalidateCache();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "LRU Cache evicted successfully."));
    }
}