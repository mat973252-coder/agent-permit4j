package example.inventory;

import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.Map;

/** An immutable, backend-owned proposal. A browser must not supply the review invocation. */
public record InventoryPreview(String reviewId, String requester, String tenant, String environment,
    String sku, int quantity, int expectedVersion, int availableBefore, int availableAfter) {

  public Map<String, String> arguments() {
    return Map.of("sku", sku, "quantity", Integer.toString(quantity),
        "expectedVersion", Integer.toString(expectedVersion));
  }

  ToolInvocation invocation() {
    // Match inventory.reserve's annotation contract; the adoption test proves the binding.
    var descriptor = new ToolDescriptor("inventory.reserve", ToolEffect.WRITE,
        Reversibility.IRREVERSIBLE, DataSensitivity.RESTRICTED);
    return new ToolInvocation(descriptor, new Principal(requester, Map.of()),
        new Action("inventory.reserve"), new Resource("inventory", sku, Map.of()),
        new InvocationContext(tenant, environment), arguments());
  }

  InventoryPreview withReviewId(String id) {
    return new InventoryPreview(id, requester, tenant, environment, sku, quantity,
        expectedVersion, availableBefore, availableAfter);
  }
}
