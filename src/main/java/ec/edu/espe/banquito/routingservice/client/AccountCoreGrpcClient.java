package ec.edu.espe.banquito.routingservice.client;

import com.banquito.core.account.AccountCoreServiceGrpc;
import com.banquito.core.account.BatchCreditRequest;
import com.banquito.core.account.BatchCreditResponse;
import com.banquito.core.account.CorporateDebitRequest;
import com.banquito.core.account.CorporateDebitResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class AccountCoreGrpcClient {

    @GrpcClient("account-core")
    private AccountCoreServiceGrpc.AccountCoreServiceBlockingStub blockingStub;

    public BatchCreditResponse batchCredit(BatchCreditRequest request) {
        return blockingStub
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .batchCredit(request);
    }

    public CorporateDebitResponse corporateDebit(CorporateDebitRequest request) {
        return blockingStub
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .corporateDebit(request);
    }
}
