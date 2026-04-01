package com.ecommerce.order_service.config;

import com.ecommerce.order_service.service.client.InventoryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced //Sirve para consultar el eureka server  en el inventoryClient
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public InventoryClient inventoryClient(WebClient.Builder builder){
        WebClient webClient = builder.
                baseUrl("http://INVENTORY-SERVICE").build();

        HttpServiceProxyFactory factory =
                HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build();

        return factory.createClient(InventoryClient.class);
    }
}
