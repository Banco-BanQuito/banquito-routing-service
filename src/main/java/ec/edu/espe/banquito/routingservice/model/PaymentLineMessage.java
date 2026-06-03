package ec.edu.espe.banquito.routingservice.model;

public class PaymentLineMessage {

    private String batchId;
    private int lineNumber;
    private String routingCode;
    private String accountDestination;
    private double amount;
    private String reference;
    private String beneficiaryName;
    private String beneficiaryEmail;
    private String transactionUuid;
    // Total records declared in the batch header — used to detect batch completion
    private int declaredTotalRecords;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public String getRoutingCode() { return routingCode; }
    public void setRoutingCode(String routingCode) { this.routingCode = routingCode; }

    public String getAccountDestination() { return accountDestination; }
    public void setAccountDestination(String accountDestination) { this.accountDestination = accountDestination; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getBeneficiaryName() { return beneficiaryName; }
    public void setBeneficiaryName(String beneficiaryName) { this.beneficiaryName = beneficiaryName; }

    public String getBeneficiaryEmail() { return beneficiaryEmail; }
    public void setBeneficiaryEmail(String beneficiaryEmail) { this.beneficiaryEmail = beneficiaryEmail; }

    public String getTransactionUuid() { return transactionUuid; }
    public void setTransactionUuid(String transactionUuid) { this.transactionUuid = transactionUuid; }

    public int getDeclaredTotalRecords() { return declaredTotalRecords; }
    public void setDeclaredTotalRecords(int declaredTotalRecords) { this.declaredTotalRecords = declaredTotalRecords; }
}
