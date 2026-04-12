package com.ayedata.ai.agent;

/**
 * Structured result from the intent classifier.
 * Immutable record capturing action, beneficiary, amount, channel, and confidence.
 */
public record ParsedIntent(String action, String beneficiary, double amount, String channel, String confidence) {
    boolean isTransfer()       { return "TRANSFER".equals(action); }
    boolean isReceive()        { return "RECEIVE".equals(action); }
    boolean isMandate()        { return "MANDATE".equals(action); }
    boolean isQueryBalance()   { return "QUERY_BALANCE".equals(action); }
    boolean isQueryTxns()      { return "QUERY_TRANSACTIONS".equals(action); }
    boolean isQuerySearch()    { return "QUERY_SEARCH".equals(action); }
    public boolean isTransactional()  { return isTransfer() || isReceive() || isMandate(); }
    public boolean isQueryTool()      { return isQueryBalance() || isQueryTxns() || isQuerySearch(); }
    public boolean isHighConfidence() { return "HIGH".equalsIgnoreCase(confidence); }
}
