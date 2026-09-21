package example.inventory;

import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.springai.AgentPermit;
import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

public final class InventoryTools {
  private final InventoryStore inventory;
  private final InventoryReviews reviews;

  InventoryTools(InventoryStore inventory, InventoryReviews reviews) {
    this.inventory = inventory;
    this.reviews = reviews;
  }

  @Tool(name = "inventory.lookup", description = "Read available demo inventory")
  @AgentPermit(resourceType = "inventory", resourceArg = "sku", effect = ToolEffect.READ, risk = RiskLevel.LOW)
  public Stock lookup(String sku) {
    return inventory.snapshot();
  }

  @Tool(name = "inventory.preview", description = "Prepare an inventory reservation for review; does not reserve stock")
  @AgentPermit(resourceType = "inventory", resourceArg = "sku", risk = RiskLevel.LOW)
  public InventoryPreview preview(String sku, int quantity, ToolContext context) {
    return reviews.preview(sku, quantity, context);
  }

  @Tool(name = "inventory.reserve", description = "Reserve only the exact approved quantity and inventory version")
  @AgentPermit(resourceType = "inventory", resourceArg = "sku")
  public Stock reserve(String sku, int quantity, int expectedVersion, ToolContext context) {
    return inventory.reserve(quantity, expectedVersion,
        (String) context.getContext().get(SpringAiToolContextKeys.PRINCIPAL_ID));
  }
}
