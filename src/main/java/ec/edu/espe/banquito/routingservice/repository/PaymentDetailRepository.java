package ec.edu.espe.banquito.routingservice.repository;

import ec.edu.espe.banquito.routingservice.model.PaymentDetail;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentDetailRepository extends MongoRepository<PaymentDetail, String> {
    boolean existsByBatchIdAndLineNumber(String batchId, int lineNumber);
}
