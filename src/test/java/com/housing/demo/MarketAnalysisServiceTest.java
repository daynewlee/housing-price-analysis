package com.housing.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.housing.demo.market.cache.LruCache;
import com.housing.demo.market.client.PythonApiClient;
import com.housing.demo.market.model.AggregateResult;
import com.housing.demo.market.model.PropertyRecord;
import com.housing.demo.market.model.WhatIfRequest;
import com.housing.demo.market.Service.MarketAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketAnalysisServiceTest {

    @Mock
    private PythonApiClient pythonApiClient;

    private LruCache<String, List<PropertyRecord>> lruCache;
    private ObjectMapper objectMapper;
    private MarketAnalysisService marketAnalysisService;

    private List<PropertyRecord> mockPropertyList;

    @BeforeEach
    void setUp() {
        lruCache = new LruCache<>();
        objectMapper = new ObjectMapper();
        marketAnalysisService = new MarketAnalysisService(pythonApiClient, lruCache, objectMapper);

        mockPropertyList = List.of(
            new PropertyRecord(
                1L, "Urban Apartment", 1200.0, 2, 2.0, 2018, 2000.0, 8.0, 8.5, 400000.0, "USD", "2026-10-01"
            ),
            new PropertyRecord(
                2L, "Suburban House", 2400.0, 4, 3.0, 1995, 6000.0, 25.0, 7.0, 300000.0, "USD", "2026-10-01"
            ),
            new PropertyRecord(
                3L, "Country Cottage", 1800.0, 3, 2.0, 1975, 15000.0, 60.0, 6.0, 200000.0, "USD", "2026-10-01"
            )
        );
    }

    @Test
    @DisplayName("Should fetch from Python API on cache miss and store data in LRU cache")
    void testGetCachedOrFreshHistory_CacheMiss() {
        when(pythonApiClient.fetchLatestHistory()).thenReturn(mockPropertyList);

        List<PropertyRecord> firstCall = marketAnalysisService.getCachedOrFreshHistory();
        List<PropertyRecord> secondCall = marketAnalysisService.getCachedOrFreshHistory();

        assertThat(firstCall).hasSize(3);
        assertThat(secondCall).hasSize(3);
        // Verify pythonApiClient was invoked only once due to LRU cache hit on second call
        verify(pythonApiClient, times(1)).fetchLatestHistory();
    }

    @Test
    @DisplayName("Should return NO_DATA status when history list is empty")
    void testAggregateByDistance_EmptyData() {
        when(pythonApiClient.fetchLatestHistory()).thenReturn(List.of());

        Map<String, Object> result = marketAnalysisService.aggregateByDistance();

        assertThat(result).containsEntry("status", "NO_DATA");
        assertThat(result).containsKey("message");
    }

    @Test
    @DisplayName("Should categorize properties into correct distance buckets")
    void testAggregateByDistance_Success() {
        when(pythonApiClient.fetchLatestHistory()).thenReturn(mockPropertyList);

        Map<String, Object> response = marketAnalysisService.aggregateByDistance();

        assertThat(response.get("status")).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<AggregateResult> aggregates = (List<AggregateResult>) response.get("aggregates");

        assertThat(aggregates).isNotEmpty();
        // 8.0 miles -> "0 - 15 miles", 25.0 miles -> "15 - 35 miles", 60.0 miles -> "55+ miles"
        assertThat(aggregates).anyMatch(a -> a.bucket().equals("0 - 15 miles") && a.count() == 1);
        assertThat(aggregates).anyMatch(a -> a.bucket().equals("15 - 35 miles") && a.count() == 1);
        assertThat(aggregates).anyMatch(a -> a.bucket().equals("55+ miles") && a.count() == 1);
    }

    @Test
    @DisplayName("Should categorize properties into correct construction era buckets")
    void testAggregateByYear_Success() {
        when(pythonApiClient.fetchLatestHistory()).thenReturn(mockPropertyList);

        Map<String, Object> response = marketAnalysisService.aggregateByYear();

        assertThat(response.get("status")).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<AggregateResult> aggregates = (List<AggregateResult>) response.get("aggregates");

        // 2018 -> "2006 - 2026", 1995 -> "1986 - 2006", 1975 -> "Older than 1986"
        assertThat(aggregates).anyMatch(a -> a.bucket().equals("2006 - 2026") && a.count() == 1);
        assertThat(aggregates).anyMatch(a -> a.bucket().equals("1986 - 2006") && a.count() == 1);
        assertThat(aggregates).anyMatch(a -> a.bucket().equals("Older than 1986") && a.count() == 1);
    }

    @Test
    @DisplayName("Should correctly filter by bedrooms and sort properties descending by price")
    void testFilterAndSort_BedroomsAndPriceDesc() {
        when(pythonApiClient.fetchLatestHistory()).thenReturn(mockPropertyList);

        // Filter: minimum 3 bedrooms, sort: price_desc
        List<PropertyRecord> filtered = marketAnalysisService.filterAndSort(null, null, 3, "price_desc");

        assertThat(filtered).hasSize(2); // IDs 2 and 3 have >= 3 bedrooms
        assertThat(filtered.get(0).id()).isEqualTo(2L); // $300,000 comes before $200,000
        assertThat(filtered.get(1).id()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should compute 5-year compound growth and trajectory in What-If analysis")
    void testRunWhatIfAnalysis_Calculates5YearProjections() {
        when(pythonApiClient.fetchLatestHistory()).thenReturn(mockPropertyList);

        WhatIfRequest request = new WhatIfRequest("Inflation Simulation", 5, 3.0, 5.5);
        Map<String, Object> result = marketAnalysisService.runWhatIfAnalysis(request);

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(result.get("sampleSize")).isEqualTo(3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projections = (List<Map<String, Object>>) result.get("projections");
        assertThat(projections).hasSize(3);

        Map<String, Object> first = projections.get(0);
        assertThat(((Number) first.get("currentPrice")).doubleValue()).isEqualTo(400000.0);
        assertThat(((Number) first.get("projectedPrice")).doubleValue()).isGreaterThan(400000.0);
        assertThat(((Number) first.get("projectedGain")).doubleValue()).isGreaterThan(0.0);
        assertThat(first).containsKey("estimatedMonthlyMortgage");
    }

    @Test
    @DisplayName("Should successfully evict cache when invalidateCache is called")
    void testInvalidateCache() {
        when(pythonApiClient.fetchLatestHistory()).thenReturn(mockPropertyList);

        marketAnalysisService.getCachedOrFreshHistory();
        assertThat(lruCache.size()).isGreaterThan(0);

        marketAnalysisService.invalidateCache();
        assertThat(lruCache.size()).isZero();
    }
}