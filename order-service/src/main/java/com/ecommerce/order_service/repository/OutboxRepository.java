package com.ecommerce.order_service.repository;

import com.ecommerce.order_service.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    // Buscamos solo los eventos que aún no han sido enviados exitosamente
    List<OutboxEvent> findByProcessedFalse();
}
