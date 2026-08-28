package com.atest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.atest.config.AtestProperties;
import org.junit.jupiter.api.Test;

/**
 * SSRF policy matrix for open-API callback URLs, no Spring context needed.
 *
 * <p>Empty allowlist (gray-release default): generic public http(s) stays usable but loopback,
 * RFC1918, link-local (incl. the 169.254.169.254 metadata endpoint), CGNAT and IPv6 ULA are
 * rejected. Non-empty allowlist: only listed hostnames / IPs / CIDR ranges pass — including
 * explicitly listed private hosts.
 */
class CallbackUrlPolicyTest {

    private static CallbackUrlPolicy policy(String... allowedHosts) {
        AtestProperties props = new AtestProperties();
        props.getCallback().setAllowedHosts(List.of(allowedHosts));
        return new CallbackUrlPolicy(props);
    }

    // -------------------------------------------------- empty allowlist mode

    @Test
    void emptyListBlocksLoopbackAndPrivateAndMetadata() {
        CallbackUrlPolicy p = policy();
        for (String url : new String[]{
                "http://127.0.0.1:8080/cb",
                "http://127.8.9.10/cb",
                "http://localhost/cb",
                "http://[::1]/cb",
                "http://0.0.0.0/cb",
                "http://10.1.2.3/cb",
                "http://172.16.0.1/cb",
                "http://192.168.1.1:9000/cb",
                "http://169.254.169.254/latest/meta-data/",
                "http://100.64.0.1/cb",
                "http://[fd00::1]/cb"}) {
            assertThat(p.rejectReason(url)).as(url).isNotNull();
        }
    }

    @Test
    void emptyListAllowsGenericPublicTargets() {
        CallbackUrlPolicy p = policy();
        // public IP literal: no DNS involved, deterministically allowed
        assertThat(p.rejectReason("http://93.184.216.34/notify")).isNull();
        assertThat(p.rejectReason("https://8.8.8.8:8443/notify")).isNull();
        // unresolvable host: allowed at this layer — the connect fails on its own and the same
        // check re-runs before every delivery attempt, catching late malicious resolutions
        assertThat(p.rejectReason("http://callback.chaos.invalid/notify")).isNull();
    }

    // ----------------------------------------------------- explicit allowlist

    @Test
    void allowlistMatchesHostnamesLiterally() {
        CallbackUrlPolicy p = policy("cb.chaos.internal");
        assertThat(p.rejectReason("http://cb.chaos.internal/notify")).isNull();
        assertThat(p.rejectReason("http://CB.Chaos.Internal:8080/notify")).isNull();
        assertThat(p.rejectReason("http://evil.chaos.internal/notify")).isNotNull();
        assertThat(p.rejectReason("http://93.184.216.34/notify")).isNotNull();
    }

    @Test
    void allowlistMatchesIpAndCidrEntries() {
        CallbackUrlPolicy p = policy("10.9.0.0/16", "127.0.0.1");
        // explicitly listed private targets are the opt-in for internal receivers
        assertThat(p.rejectReason("http://10.9.1.2:8080/notify")).isNull();
        assertThat(p.rejectReason("http://10.9.255.254/notify")).isNull();
        assertThat(p.rejectReason("http://127.0.0.1:9000/notify")).isNull();
        // outside the range / unlisted stays rejected, private or public alike
        assertThat(p.rejectReason("http://10.8.0.1/notify")).isNotNull();
        assertThat(p.rejectReason("http://127.0.0.2/notify")).isNotNull();
        assertThat(p.rejectReason("http://93.184.216.34/notify")).isNotNull();
        assertThat(p.rejectReason("http://some.host.example/notify")).isNotNull();
    }

    @Test
    void malformedUrlsAndHostsAreRejectedInBothModes() {
        assertThat(policy().rejectReason("http://")).isNotNull();
        assertThat(policy("x.internal").rejectReason("http://")).isNotNull();
        assertThat(policy().rejectReason("not a url")).isNotNull();
    }
}
