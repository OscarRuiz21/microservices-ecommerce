package com.ecommerce.notification_service.listener;

import com.ecommerce.notification_service.event.OrderCancelledEvent;
import com.ecommerce.notification_service.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Slf4j
@RabbitListener(queues = "notification-queue")
public class OrderEventsListener {

    private final JavaMailSender mailSender;

//    @RabbitListener(queues = "notification-queue")
    @RabbitHandler
    public void handleOrderConfirmedEvent(OrderConfirmedEvent event) {

        log.info("🔔 Pedido confirmado para Orden: {}", event.orderNumber());

//      throw new RuntimeException("Error SMTP");


        //try {

            SimpleMailMessage message = new SimpleMailMessage();
//            message.setFrom("oscar.ruizhu@gmail.com");
            message.setTo(event.email());
            message.setSubject("Orden Confirmada - " + event.orderNumber());
            message.setText("Hola!\n\n" +
                    "Tu pedido con número " + event.orderNumber() + " ha sido recibido exitosamente.\n" +
                    "Pronto recibirás más noticias sobre el envío.\n\n" +
                    "Gracias por comprar con nosotros!");
            mailSender.send (message);

            log.info("✅ Correo enviado exitosamente a: {}", event.email());

//        } catch (Exception e) {
//            log.error("Error al enviar el correo: {}", e.getMessage());
//        }

    }

//    @RabbitListener(queues = "notification-queue")
    @RabbitHandler
    public void handleOrderCancelledEvent(OrderCancelledEvent event) {
        log.warn("🚨 Enviando correo de cancelación para la orden: {}", event.orderNumber());

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(event.email());
        message.setSubject("Actualización de tu pedido - " + event.orderNumber());
        message.setText("Lamentamos informarte que tu pedido ha sido cancelado.\n\n" +
                "Motivo: " + event.reason() + ".\n" +
                "Si se realizó algún cargo, será reembolsado a la brevedad.");

        mailSender.send(message);
        log.info("📧 Correo de disculpa enviado con éxito a {}", event.email());
    }
}
