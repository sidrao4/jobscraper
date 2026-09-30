package com.jobaggregator.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpConfig {

    @Bean
    RestClient restClient(AppProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.ingest().requestTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.ingest().requestTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "job-aggregator/0.1")
                .build();
    }
}
