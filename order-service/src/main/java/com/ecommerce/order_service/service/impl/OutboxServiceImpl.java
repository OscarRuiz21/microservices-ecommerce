package com.ecommerce.order_service.service.impl;

import com.ecommerce.order_service.event.OrderPlacedEvent;
import com.ecommerce.order_service.model.OutboxEvent;
import com.ecommerce.order_service.repository.OutboxRepository;
import com.ecommerce.order_service.service.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void saveOrderPlacedEvent(OrderPlacedEvent event, boolean isProcessed) {

        String payload = objectMapper.writeValueAsString(event);

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateId(event.orderNumber())
                .type("ORDER_PLACED")
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .processed(isProcessed)
                .build();
        outboxRepository.save(outboxEvent);
        log.info("💾 Evento asegurado en Outbox: {}", event.orderNumber());

    }

    @Override
    public List<OutboxEvent> getPendingEvents() {
        return outboxRepository.findByProcessedFalse();
    }

    @Override
    public void markAsProcessed(Long id) {
        outboxRepository.findById(id).ifPresent( event -> {
            event.setProcessed(true);
            outboxRepository.save(event);
            log.info("✅ Evento {} marcado como procesado", id);
        });
    }


}
