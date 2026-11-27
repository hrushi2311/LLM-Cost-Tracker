package dev.llmcosttracker.attribution;

/** How an agent was discovered; stored in {@code agent.source}. */
public enum AgentSource {
    HEADER("header"),
    PATH("path"),
    FINGERPRINT("fingerprint"),
    UNATTRIBUTED("unattributed");

    private final String dbValue;

    AgentSource(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
