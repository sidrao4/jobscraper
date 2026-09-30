package com.jobaggregator.ingest;

import java.util.concurrent.CompletableFuture;

import com.jobaggregator.config.AppProperties;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IngestionScheduler {

    private final IngestionService ingestion;
    private final AppProperties props;

    public IngestionScheduler(IngestionService ingestion, AppProperties props) {
        this.ingestion = ingestion;
        this.props = props;
    }

    @Scheduled(cron = "${app.ingest.cron}")
    public void scheduledRun() {
        ingestion.runOnce();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.ingest().pollOnStartup()) {
            CompletableFuture.runAsync(ingestion::runOnce, Thread.ofVirtual()::start);
        }
    }
}
