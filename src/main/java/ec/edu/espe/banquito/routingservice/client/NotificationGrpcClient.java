package ec.edu.espe.banquito.routingservice.client;

import com.banquito.payswitch.notification.NotificationRequest;
import com.banquito.payswitch.notification.NotificationServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

@Component
public class NotificationGrpcClient {

    @GrpcClient("notification")
    private NotificationServiceGrpc.NotificationServiceFutureStub futureStub;

    // Fire-and-forget: returns immediately without waiting for the response
    public void sendNotification(NotificationRequest request) {
        futureStub.sendNotification(request);
    }
}
