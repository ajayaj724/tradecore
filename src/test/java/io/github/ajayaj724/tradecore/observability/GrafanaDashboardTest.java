package io.github.ajayaj724.tradecore.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GrafanaDashboardTest {

    private static final Path DASHBOARD = Path.of("infra/grafana/provisioning/dashboards/tradecore.json");
    private static final Path PROVIDER = Path.of("infra/grafana/provisioning/dashboards/dashboards.yaml");

    @Test
    void dashboardIsWellFormedAndReferencesKeyMetrics() throws Exception {
        assertThat(Files.exists(PROVIDER)).isTrue();

        JsonNode root = new ObjectMapper().readTree(Files.readString(DASHBOARD));
        JsonNode panels = root.get("panels");
        assertThat(panels).isNotNull();
        assertThat(panels.isArray()).isTrue();
        assertThat(panels.size()).isGreaterThanOrEqualTo(6);

        String json = root.toString();
        assertThat(json).contains("tradecore_reconciliation_drift_pairs");
        assertThat(json).contains("tradecore_order_fill_latency_seconds_bucket");
        assertThat(json).contains("tradecore_orders_submitted_total");
        assertThat(json).contains("tradecore_risk_rejections_total");
        assertThat(json).contains("tradecore_events_registry_lag");
    }
}
