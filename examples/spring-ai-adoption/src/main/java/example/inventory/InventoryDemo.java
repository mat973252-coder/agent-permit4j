package example.inventory;

import io.github.mat973252.agentpermit.core.Principal;
import java.time.Clock;
import java.util.Map;
import org.springframework.ai.util.JsonHelper;

public final class InventoryDemo {
  private InventoryDemo() {}

  public static void main(String[] args) {
    var app = new InventoryApplication(Clock.systemUTC());
    var json = new JsonHelper();
    var read = call(app, "inventory.lookup", "{\"sku\":\"internal-sku-7\"}", null);
    var proposed = call(app, "inventory.preview", "{\"sku\":\"internal-sku-7\",\"quantity\":2}", null);
    var preview = json.fromJson((String) json.fromJsonToMap(proposed).get("output"), InventoryPreview.class);
    var input = json.toJson(preview.arguments());
    var pending = call(app, "inventory.reserve", input, null);
    var review = app.approve(preview.reviewId(), new Principal("reviewer-a", Map.of("role", "reviewer", "tenant", "tenant-a")));
    var executed = call(app, "inventory.reserve", input, preview.reviewId());
    var retry = call(app, "inventory.reserve", input, preview.reviewId());
    System.out.println("ADOPTION tools=" + app.tools().size() + " lookup=" + json.fromJsonToMap(read).get("outcome")
        + " pending=" + json.fromJsonToMap(pending).get("outcome") + " reviewed=" + review.permitted()
        + " result=" + json.fromJsonToMap(executed).get("outcome") + " writes=" + app.inventory().writes()
        + " available=" + app.inventory().available() + " retrySame=" + executed.equals(retry));
  }

  private static String call(InventoryApplication app, String tool, String input, String approval) {
    return app.tools().stream().filter(t -> t.getToolDefinition().name().equals(tool)).findFirst().orElseThrow()
        .call(input, InventoryApplication.context("operator-a", "tenant-a", tool, approval));
  }
}
