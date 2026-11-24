package dev.llmcosttracker.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ProxyHeadersTest {

    @Test
    void requestKeepsCredentialsButDropsTrackerHopByHopAndClientManagedHeaders() {
        HttpHeaders in = new HttpHeaders();
        in.add("Authorization", "Bearer sk-test");
        in.add("x-api-key", "sk-ant-test");
        in.add("Content-Type", "application/json");
        in.add("OpenAI-Beta", "assistants=v2");
        in.add("X-Tracker-Key", "trk_secret");
        in.add("x-tracker-run-id", "run-1");
        in.add("X-Tracker-Tag-Team", "search");
        in.add("Host", "localhost:8080");
        in.add("Content-Length", "42");
        in.add("Connection", "keep-alive, X-Custom-Hop");
        in.add("X-Custom-Hop", "1");
        in.add("Transfer-Encoding", "chunked");
        in.add("Accept-Encoding", "gzip");

        HttpHeaders out = new HttpHeaders();
        ProxyHeaders.copyRequestHeaders(in, out, false);

        assertThat(out.toSingleValueMap()).containsOnlyKeys(
                "Authorization", "x-api-key", "Content-Type", "OpenAI-Beta", "Accept-Encoding");
        assertThat(out.getFirst("Authorization")).isEqualTo("Bearer sk-test");
    }

    @Test
    void acceptEncodingDroppedWhenTheResponseMustBeParsed() {
        HttpHeaders in = new HttpHeaders();
        in.add("Accept-Encoding", "gzip, br");
        HttpHeaders out = new HttpHeaders();

        ProxyHeaders.copyRequestHeaders(in, out, true);

        assertThat(out.containsKey("Accept-Encoding")).isFalse();
    }

    @Test
    void responseDropsOnlyHopByHopHeaders() {
        HttpHeaders in = new HttpHeaders();
        in.add("Content-Type", "application/json");
        in.add("Content-Length", "100");
        in.add("x-request-id", "req_1");
        in.add("openai-processing-ms", "321");
        in.add("Transfer-Encoding", "chunked");
        in.add("Connection", "close");

        HttpHeaders out = new HttpHeaders();
        ProxyHeaders.copyResponseHeaders(in, out);

        assertThat(out.toSingleValueMap()).containsOnlyKeys(
                "Content-Type", "Content-Length", "x-request-id", "openai-processing-ms");
    }
}
