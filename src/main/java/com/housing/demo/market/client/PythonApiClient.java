package com.housing.demo.market.client;

import com.housing.demo.market.model.PropertyRecord;
import com.housing.demo.market.model.PythonHistoryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Collections;
import java.util.List;

@Component
public class PythonApiClient {

    private final RestClient restClient;
    private final String pythonApiUrl;

    public PythonApiClient(@Value("${python.api.url:http://housing-api:8000}") String pythonApiUrl) {
        this.pythonApiUrl = pythonApiUrl.endsWith("/")
            ? pythonApiUrl.substring(0, pythonApiUrl.length() - 1)
            : pythonApiUrl;
        this.restClient = RestClient.builder().build();
    }

    /**
     * 从 Python 历史记录接口拉取最新的房产记录供 Java 缓存和分析使用
     */
    public List<PropertyRecord> fetchLatestHistory() {
        try {
            URI targetUri = URI.create(this.pythonApiUrl + "/api/estimates/history?page=1&page_size=25");

            PythonHistoryResponse response = restClient.get()
                .uri(targetUri)
                .retrieve()
                .body(PythonHistoryResponse.class);

            return (response != null && response.records() != null)
                ? response.records()
                : Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Failed to fetch history from Python API: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}