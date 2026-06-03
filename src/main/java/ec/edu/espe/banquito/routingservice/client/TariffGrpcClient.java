package ec.edu.espe.banquito.routingservice.client;

import com.banquito.payswitch.tariff.TariffRequest;
import com.banquito.payswitch.tariff.TariffResponse;
import com.banquito.payswitch.tariff.TariffServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class TariffGrpcClient {

    @GrpcClient("tariff")
    private TariffServiceGrpc.TariffServiceBlockingStub blockingStub;

    public TariffResponse calculateTariff(TariffRequest request) {
        return blockingStub
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .calculateTariff(request);
    }
}
