package com.audigo.domain.chat.service;

import com.audigo.domain.chat.config.ChatMessageSearchOutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name="audigo.chat.search-sync.worker-enabled",havingValue="true")
public class ChatMessageSearchSyncWorker {
    private static final Logger log=LoggerFactory.getLogger(ChatMessageSearchSyncWorker.class);
    private final ChatMessageSearchOutboxStore store;
    private final ChatMessageEsIndexer indexer;
    private final ChatMessageSearchOutboxProperties properties;
    public ChatMessageSearchSyncWorker(ChatMessageSearchOutboxStore store, ChatMessageEsIndexer indexer, ChatMessageSearchOutboxProperties properties) {
        this.store=store;this.indexer=indexer;this.properties=properties;
    }

    @Scheduled(scheduler="chatSearchSyncScheduler",fixedDelayString="${audigo.chat.search-sync.poll-delay-millis:500}")
    public void poll() {
        if(!properties.workerEnabled())return;
        for(int i=0;i<properties.maxPerCycle()&&!Thread.currentThread().isInterrupted();i++) {
            try {if(!processOne())break;}
            catch(RuntimeException exception) {log.warn("chat_search_sync_cycle_failed code=STORE_OR_WORKER_FAILURE");break;}
        }
    }

    public boolean processOne() {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Sync worker must run outside a DB transaction");
        var claimed=store.claim();
        if(claimed.isEmpty())return false;
        var event=claimed.get();
        try {indexer.index(event);}
        catch(ChatMessageEsIndexer.IndexingException exception) {
            boolean owned=store.markFailure(event,exception.retryable(),exception.getMessage());
            log.warn("chat_search_sync_attempt_failed event_id={} attempt={} code={} retryable={} owned={}",event.eventId(),event.attemptCount(),exception.getMessage(),exception.retryable(),owned);
            return true;
        }
        // If the DB acknowledgement fails after ES succeeds, keep the lease for later recovery.
        boolean owned=store.markIndexed(event);
        log.info("chat_search_sync_indexed event_id={} attempt={} owned={}",event.eventId(),event.attemptCount(),owned);
        return true;
    }
}
