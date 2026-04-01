package com.ecommerce.inventory_service.service.impl;

import com.ecommerce.inventory_service.dto.InventoryRequest;
import com.ecommerce.inventory_service.dto.InventoryResponse;
import com.ecommerce.inventory_service.exception.ResourceNotFoundException;
import com.ecommerce.inventory_service.mapper.InventoryMapper;
import com.ecommerce.inventory_service.model.Inventory;
import com.ecommerce.inventory_service.repository.InventoryRepository;
import com.ecommerce.inventory_service.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceImpl implements InventoryService {
    private final InventoryRepository inventoryRepository;
    private final InventoryMapper inventoryMapper;

    @Value("${inventory.allow-backorders:false}")
    private boolean allowBackorders;

    @Override
    @Transactional(readOnly = true)
    public boolean isInStock(String sku, Integer quantity) {

        if(allowBackorders){
            log.warn("MODO BACKORDER ACTIVO: Autorizando stock para SKU: {}", sku);
            return true;
        }

        return inventoryRepository.findBySku(sku)
                .map(inventory -> inventory.getQuantity() >= quantity)
                .orElse(false);
    }

    @Override
    public InventoryResponse createInventory(InventoryRequest inventoryRequest) {
        boolean exists = inventoryRepository.existsBySku(inventoryRequest.sku());
        if(exists){
            throw new RuntimeException("El inventario para el SKU " + inventoryRequest.sku() + " ya existe");
        }

        Inventory inventory = inventoryMapper.toModel(inventoryRequest);
        Inventory savedInventory = inventoryRepository.save(inventory);

        log.info("Inventario creado para el SKU: {}", savedInventory.getSku());

        return inventoryMapper.toResponse(savedInventory);
    }

    @Override
    public List<InventoryResponse> getAllInventory() {
        return inventoryRepository.findAll()
                .stream()
                .map(inventoryMapper::toResponse)
                .toList();
    }

    @Override
    public InventoryResponse updateInventory(Long id, InventoryRequest inventoryRequest) {
        Inventory inventory = inventoryRepository.findById(id)
                .orElseThrow(
                        () -> new ResourceNotFoundException("Inventorio", "id", id)
                );

        inventory.setSku(inventoryRequest.sku());
        inventory.setQuantity(inventoryRequest.quantity());

        Inventory updateInventory = inventoryRepository.save(inventory);

        log.info("Inventario actualizado para el ID: {}", id);

        return inventoryMapper.toResponse(updateInventory);
    }

    @Override
    public void deleteInventory(Long id) {
        Boolean exist =  inventoryRepository.existsById(id);
        if(exist){
            throw new RuntimeException("El inventario con ID " + id + " no existe");
        }
        log.info("Inventario eliminado para el ID: {}", id);
        inventoryRepository.deleteById(id);
    }

    @Override
    public void reduceStock(String sku, Integer quantity) {
        Inventory inventory = inventoryRepository.findBySku(sku)
                .orElseThrow(
                        () -> new RuntimeException("Producto no encontrado: " + sku)
                );
        if(inventory.getQuantity() < quantity){
            throw new RuntimeException("Stock insuficiente para: " + sku);
        }
        inventory.setQuantity(inventory.getQuantity()-quantity);
        inventoryRepository.save(inventory);
        log.info("Stock reducido para el SKU: {}", sku);
    }
}
