package com.ecommerce.order_service.service;

import com.ecommerce.order_service.event.OrderPlacedEvent;
import com.ecommerce.order_service.model.OutboxEvent;

import java.util.List;

public interface OutboxService {
    // Método para guardar eventos de órdenes creadas
    void saveOrderPlacedEvent(OrderPlacedEvent event, boolean isProcessed);
    List<OutboxEvent> getPendingEvents();
    void markAsProcessed(Long id);

}
