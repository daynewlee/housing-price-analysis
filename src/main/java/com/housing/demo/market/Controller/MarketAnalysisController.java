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
     * 1. 过滤和排序房产列表（支持 minPrice, maxPrice, minBedrooms, sortBy）
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
     * 4. What-If 推演分析接口（限定 year_built >= 2026）
     */
    @PostMapping("/what-if")
    public ResponseEntity<?> analyzeWhatIf(@RequestBody WhatIfRequest request) {
        if (request.yearBuilt() < 2026) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid Year: What-If simulation strictly requires year_built >= 2026."));
        }

        try {
            return ResponseEntity.ok(analysisService.runWhatIfAnalysis(request));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Python ML service unavailable: " + e.getMessage()));
        }
    }

    /**
     * 5. Refresh cache (Python 端新增估价后可调用刷新）
     */
    @PostMapping("/cache/refresh")
    public ResponseEntity<Map<String, String>> refreshCache() {
        analysisService.invalidateCache();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "LRU Cache evicted successfully."));
    }
}