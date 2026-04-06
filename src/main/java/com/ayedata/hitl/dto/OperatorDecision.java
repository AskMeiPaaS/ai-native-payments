package com.ayedata.hitl.dto;

/**
 * Request DTO for operator decisions on HITL escalations.
 */
public class OperatorDecision {
    private String operatorId;
    private String operatorNotes;
    private String actionIntent;

    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }

    public String getOperatorNotes() { return operatorNotes; }
    public void setOperatorNotes(String operatorNotes) { this.operatorNotes = operatorNotes; }

    public String getActionIntent() { return actionIntent; }
    public void setActionIntent(String actionIntent) { this.actionIntent = actionIntent; }
}
