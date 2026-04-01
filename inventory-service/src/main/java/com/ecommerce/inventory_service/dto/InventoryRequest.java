package com.ecommerce.inventory_service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record InventoryRequest (
        @NotBlank(message = "El SKU es obligatorio")
        String sku,
        @Min(value = 0, message = "La cantidad debe ser mayor o igual a cero")
        Integer quantity){
}
