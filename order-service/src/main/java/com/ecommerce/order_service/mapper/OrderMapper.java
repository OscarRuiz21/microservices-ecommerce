package com.ecommerce.order_service.mapper;

import com.ecommerce.order_service.dto.OrderLineItemsRequest;
import com.ecommerce.order_service.dto.OrderLineItemsResponse;
import com.ecommerce.order_service.dto.OrderRequest;
import com.ecommerce.order_service.dto.OrderResponse;
import com.ecommerce.order_service.model.Order;
import com.ecommerce.order_service.model.OrderLineItems;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    // 1. De Request a Entidad
    Order toOrder(OrderRequest orderRequest);

    // (MapStruct usará este método para convertir cada elemento de la lista del Request)
    OrderLineItems toOrderLineItems(OrderLineItemsRequest orderLineItemsRequest);


    // 2. De Entidad a Response
    OrderResponse toOrderResponse(Order order);

    // (MapStruct usará este método para convertir cada elemento de la lista hacia el Response)
    OrderLineItemsResponse toOrderLineItemsResponse(OrderLineItems orderLineItems);
}