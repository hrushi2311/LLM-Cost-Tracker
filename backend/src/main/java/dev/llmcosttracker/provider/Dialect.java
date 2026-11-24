package dev.llmcosttracker.provider;

/** Which API family a provider speaks, which decides how its responses are parsed. */
public enum Dialect {
    OPENAI,
    ANTHROPIC
}
