package example.inventory;

/** Local business state; production inventory requires a conditional database write. */
public final class InventoryStore {
  private int available = 10;
  private int version;
  private int writes;
  private String lastOperator;

  public synchronized Stock snapshot() {
    return new Stock("internal-sku-7", available, version);
  }

  public synchronized Stock reserve(int quantity, int expectedVersion, String operator) {
    if (quantity <= 0 || quantity > available || expectedVersion != version) {
      throw new IllegalStateException("inventory precondition changed");
    }
    available -= quantity;
    version++;
    writes++;
    lastOperator = operator;
    return snapshot();
  }

  public synchronized int available() { return available; }
  public synchronized int writes() { return writes; }
  public synchronized String lastOperator() { return lastOperator; }
}
