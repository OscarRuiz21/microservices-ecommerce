package com.ecommerce.order_service.service;

import com.ecommerce.order_service.dto.OrderRequest;
import com.ecommerce.order_service.dto.OrderResponse;
import com.ecommerce.order_service.model.OrderStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface OrderService {
//    CompletableFuture<OrderResponse> placeOrder(OrderRequest orderRequest, String usedId); // Create
    OrderResponse placeOrder(OrderRequest orderRequest, String userId);
    //List<OrderResponse> getAllOrders();
    List<OrderResponse> getOrders(String userId, boolean isAdmin);                  // Read All

    OrderResponse getOrderById(Long id);                 // Read One
    void deleteOrder(Long id);                           // Delete

    void updateOrderStatus(String orderNumber, OrderStatus status);
}
