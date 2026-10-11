package pzobserver.v3;
import java.util.*;

/** Getter-only fixtures for the shared per-item food reporting contract. */
public class FoodDetailsTest {
    public static class FreezeContainer {
        boolean freezer;
        public boolean isFreezer() { return freezer; }
    }
    public static class Food extends InventoryReaderTest.Item {
        float hunger = -0.1f, base = -0.2f;
        float freezingProgress;
        FreezeContainer outer = new FreezeContainer();
        boolean fresh = true, rotten, frozen, freezing, thawing, cooked, burnt, cookable = true;
        Food(int id) { super(id, "Steak", 1); }
        public boolean IsFood() { return true; }
        public String getCategory() { return "Food"; }
        public String getDisplayCategory() { return "Food"; }
        public float getHungChange() { return hunger; }
        public float getBaseHunger() { return base; }
        public boolean isFresh() { return fresh; }
        public boolean isRotten() { return rotten; }
        public boolean isFrozen() { return frozen; }
        public boolean isFreezing() { throw new AssertionError("Mutating freezing getter called"); }
        public boolean isThawing() { throw new AssertionError("Mutating thawing getter called"); }
        public boolean canBeFrozen() { return true; }
        public float getFreezingTime() { return freezingProgress; }
        public FreezeContainer getOutermostContainer() { return outer; }
        public boolean isCooked() { return cooked; }
        public boolean isBurnt() { return burnt; }
        public boolean isCookable() { return cookable; }
        public boolean isbDangerousUncooked() { return true; }
    }
    static Map<String,Object> describe(Food item) throws Exception {
        Map<String,Object> row = new LinkedHashMap<>();
        NearbyFoodReader2.describeItem(item, row);
        return row;
    }
    public static void main(String[] args) throws Exception {
        Food half = new Food(201);
        Map<String,Object> row = describe(half);
        assert Double.valueOf(0.5).equals(row.get("portion_fraction_remaining")) : row;
        assert row.get("portion_basis").equals("derived current/base hunger; not servings or original package size");
        assert row.get("cooking_state").equals("raw") && row.get("dangerous_uncooked").equals(true);
        assert row.containsKey("freshness_time_remaining") && row.get("freshness_time_remaining") == null;
        half.hunger = 0;
        assert describe(half).get("portion_fraction_remaining").equals(0.0);
        half.base = 0;
        assert describe(half).get("portion_fraction_remaining") == null : "Zero baseline treated as full";
        half.base = -0.2f; half.hunger = -0.3f;
        assert describe(half).get("portion_fraction_remaining") == null : "Out-of-range ratio clamped to full";
        half.hunger = Float.NaN;
        assert describe(half).get("portion_fraction_remaining") == null;
        assert !InventoryReader.json(describe(half)).contains("NaN");
        half.hunger = -0.1f; half.fresh = false; half.freezingProgress = 50; half.frozen = true; half.cooked = true;
        row = describe(half);
        assert row.get("freshness").equals("stale") && row.get("thawing").equals(true);
        assert row.get("cooking_state").equals("cooked") && row.get("dangerous_uncooked").equals(false);
        half.rotten = true; half.burnt = true;
        assert describe(half).get("freshness").equals("rotten");
        assert describe(half).get("cooking_state").equals("burnt");
        half.burnt = false; half.cooked = false; half.cookable = false;
        assert describe(half).get("cooking_state").equals("not cookable");
        Map<String,Object> tool = new LinkedHashMap<>();
        FoodDetails.describe(new InventoryReaderTest.Item(203,"Pot",1),tool);
        assert tool.get("is_food").equals(false);
        assert tool.containsKey("portion_fraction_remaining") && tool.get("portion_fraction_remaining") == null;
        assert !tool.containsKey("cooked") && !tool.containsKey("freshness");
        // Same name, different remaining amount/state must remain separate item records.
        Food full = new Food(202); full.hunger = full.base; full.outer.freezer = true; full.freezingProgress = 50;
        InventoryReaderTest.Container root = new InventoryReaderTest.Container();
        root.items.add(half); root.items.add(full);
        List<Map<String,Object>> rows = new ArrayList<>();
        InventoryReader.collect(root, "Inventory", new IdentityHashMap<>(),
            Collections.newSetFromMap(new IdentityHashMap<>()), rows, new ArrayList<>(), 0);
        assert rows.size() == 2;
        assert rows.get(0).get("portion_fraction_remaining").equals(0.5);
        assert rows.get(1).get("portion_fraction_remaining").equals(1.0);
        assert rows.get(1).containsKey("freezing") && rows.get(1).get("freezing") == null;
        assert rows.get(1).get("thawing") == null : "Assumed freezer power";
        assert rows.get(1).get("freezing_progress_fraction").equals(0.5);
        full.freezingProgress = 100;
        assert describe(full).get("freezing").equals(false);
        full.freezingProgress = 0;
        assert describe(full).get("thawing").equals(false);
        System.out.println("Shared food portions, unavailable values, states and per-item distinctions passed.");
    }
}
