package com.ecommerce.order_service.listener;


import com.ecommerce.order_service.event.OrderPlacedEvent;
import com.ecommerce.order_service.model.OrderStatus;
import com.ecommerce.order_service.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Slf4j
public class OrderEventsListener {
    private final OrderService orderService;

    @RabbitListener(queues = "order-confirmed-queue")
    public void handleOrderConfirmed(OrderPlacedEvent event){
        orderService.updateOrderStatus(event.orderNumber(), OrderStatus.CONFIRMED);
    }

    @RabbitListener(queues = "order-cancelled-queue")
    public void handleOrderCancelled(OrderPlacedEvent event) {
        orderService.updateOrderStatus(event.orderNumber(), OrderStatus.CANCELLED);
    }
}
