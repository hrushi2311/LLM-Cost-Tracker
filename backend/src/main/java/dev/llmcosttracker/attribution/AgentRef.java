package dev.llmcosttracker.attribution;

/**
 * Which agent a call belongs to, as worked out from the request alone (no database access). The batch writer turns
 * it into an {@code agent.id}: alias lookup, then upsert.
 *
 * @param source      how the agent was identified
 * @param name        agent slug for header/path/unattributed; null for fingerprint agents (named by the writer)
 * @param fingerprint SHA-256 hex of the normalised system prompt, for fingerprint agents only
 */
public record AgentRef(AgentSource source, String name, String fingerprint) {

    public static final String UNATTRIBUTED_NAME = "unattributed";

    public static AgentRef header(String slug) {
        return new AgentRef(AgentSource.HEADER, slug, null);
    }

    public static AgentRef path(String slug) {
        return new AgentRef(AgentSource.PATH, slug, null);
    }

    public static AgentRef fingerprint(String sha256Hex) {
        return new AgentRef(AgentSource.FINGERPRINT, null, sha256Hex);
    }

    public static AgentRef unattributed() {
        return new AgentRef(AgentSource.UNATTRIBUTED, UNATTRIBUTED_NAME, null);
    }

    /** Alias key checked before lookup: the slug, or {@code fp:<hash>} for fingerprint agents. */
    public String aliasKey() {
        return source == AgentSource.FINGERPRINT ? "fp:" + fingerprint : name;
    }
}
