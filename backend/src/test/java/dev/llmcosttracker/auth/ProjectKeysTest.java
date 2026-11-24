package dev.llmcosttracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectKeysTest {

    @Test
    void keysAreTrkPrefixedBase62OfThirtyTwoBytes() {
        String key = ProjectKeys.generate();

        assertThat(key).startsWith("trk_").matches("trk_[0-9A-Za-z]+");
        // 32 bytes = 256 bits; base62 needs at most 43 characters.
        assertThat(key.length() - 4).isBetween(40, 43);
    }

    @Test
    void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(ProjectKeys.generate());
        }
        assertThat(keys).hasSize(1000);
    }

    @Test
    void base62EncodesKnownValues() {
        assertThat(ProjectKeys.base62(new byte[] {0})).isEqualTo("0");
        assertThat(ProjectKeys.base62(new byte[] {61})).isEqualTo("z");
        assertThat(ProjectKeys.base62(new byte[] {62})).isEqualTo("10");
        assertThat(ProjectKeys.base62(new byte[] {(byte) 0xFF})).isEqualTo("47"); // 255 = 4*62 + 7
    }

    @Test
    void hashIsSha256HexAndLast4IsTheKeyTail() {
        assertThat(ProjectKeys.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ProjectKeys.last4("trk_abcdWXYZ")).isEqualTo("WXYZ");
    }
}
