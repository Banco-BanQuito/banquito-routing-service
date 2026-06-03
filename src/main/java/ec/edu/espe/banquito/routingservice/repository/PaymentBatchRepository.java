package ec.edu.espe.banquito.routingservice.repository;

import ec.edu.espe.banquito.routingservice.model.PaymentBatch;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface PaymentBatchRepository extends MongoRepository<PaymentBatch, String> {
    Optional<PaymentBatch> findByBatchId(String batchId);
}
