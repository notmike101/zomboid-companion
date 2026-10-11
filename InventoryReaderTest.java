package pzobserver.v3;
import java.util.*;

public class InventoryReaderTest {
    public static class Item {
        int id; String name; int count; Container bag; boolean hidden;
        Item(int id, String name, int count) { this.id=id; this.name=name; this.count=count; }
        public int getID() { return id; }
        public String getName() { return name; }
        public String getFullType() { return "Test." + name; }
        public int getCount() { return count; }
        public int getCondition() { return 5; }
        public int getConditionMax() { return 10; }
        public boolean IsInventoryContainer() { return bag != null; }
        public boolean isHidden() { return hidden; }
        public boolean IsFood() { return false; }
        public String getCategory() { return "Normal"; }
        public String getDisplayCategory() { return "Normal"; }
        public Container getInventory() { return bag; }
    }
    public static class Container {
        List<Item> items = new ArrayList<>();
        public List<Item> getItems() { return items; }
    }
    public static void main(String[] args) throws Exception {
        Container root = new Container();
        Item pack = new Item(1, "Pack", 1);
        pack.bag = new Container();
        Item nails = new Item(2, "Nails", 7);
        pack.bag.items.add(nails);
        root.items.add(pack);
        root.items.add(new Item(3, "Quoted\"\nItem", 1));
        Item internal = new Item(4, "Internal model", 1);
        internal.hidden = true;
        root.items.add(internal);
        List<Map<String,Object>> rows = new ArrayList<>();
        IdentityHashMap<Object,List<String>> equipped = new IdentityHashMap<>();
        equipped.put(pack, List.of("worn:Back"));
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        InventoryReader.collect(root, "Inventory", equipped, seen, rows, new ArrayList<>(), 0);
        assert rows.size() == 3 : rows;
        assert rows.get(1).get("container").equals("Inventory / Pack [1]");
        assert rows.get(1).get("count").equals(7);
        assert rows.get(0).get("equipment").equals(List.of("worn:Back"));
        assert seen.size() == 4;
        assert rows.stream().noneMatch(row -> row.get("name").equals("Internal model"));
        IdentityHashMap<Object,List<String>> sameEquipment = new IdentityHashMap<>();
        sameEquipment.put(pack, new ArrayList<>(List.of("worn:Back")));
        assert InventoryReader.sameEquipment(equipped, sameEquipment);
        sameEquipment.put(pack, List.of("primary hand"));
        assert !InventoryReader.sameEquipment(equipped, sameEquipment);
        assert InventoryReader.json(rows).contains("Quoted\\\"\\nItem");
        assert InventoryReader.json(List.of("\t\r\b\f\\")).equals("[\"\\t\\r\\b\\f\\\\\"]");
        // A malformed bag cycle must fail instead of claiming a complete inventory.
        pack.bag.items.add(pack);
        boolean rejected = false;
        try {
            InventoryReader.collect(root, "Inventory", equipped,
                Collections.newSetFromMap(new IdentityHashMap<>()), new ArrayList<>(), new ArrayList<>(), 0);
        } catch (IllegalStateException expected) { rejected = true; }
        assert rejected : "Cycle was accepted";
        System.out.println("Inventory traversal, counts, equipment, escaping and cycle checks passed.");
    }
}
