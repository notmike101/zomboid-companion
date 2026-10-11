package pzobserver.v3;
import java.util.*;

public class NearbyFoodReaderTest {
    public static class Food extends FoodDetailsTest.Food {
        boolean fresh = true, rotten, frozen, cooked, burnt;
        Food() { super(99); name = "Vegetable"; count = 3; }
        public boolean IsFood() { return true; }
        public String getCategory() { return "Food"; }
        public String getDisplayCategory() { return "Food"; }
        public boolean isFresh() { return fresh; }
        public boolean isRotten() { return rotten; }
        public boolean isFrozen() { return frozen; }
        public boolean isCooked() { return cooked; }
        public boolean isBurnt() { return burnt; }
    }
    public static class Tool extends InventoryReaderTest.Item {
        Tool() { super(100, "Pot", 1); }
        public boolean IsFood() { return false; }
        public String getCategory() { return "Normal"; }
        public String getDisplayCategory() { return "Cooking"; }
    }
    public static class Container extends InventoryReaderTest.Container {
        boolean explored = true, locked; int reads;
        public boolean isExplored() { return explored; }
        public boolean isLockedToCharacter(Object player) { return locked; }
        public List<InventoryReaderTest.Item> getItems() { reads++; return items; }
    }
    public static void main(String[] args) throws Exception {
        Food food = new Food();
        Map<String,Object> row = new LinkedHashMap<>();
        NearbyFoodReader2.describeItem(food, row);
        assert row.get("freshness").equals("fresh") : row;
        food.fresh = false;
        NearbyFoodReader2.describeItem(food, row);
        assert row.get("freshness").equals("stale") : row;
        food.rotten = true; food.frozen = true; food.cooked = true;
        NearbyFoodReader2.describeItem(food, row);
        assert row.get("freshness").equals("rotten") : row;
        assert row.get("frozen").equals(true) && row.get("cooked").equals(true);
        Map<String,Object> tool = new LinkedHashMap<>();
        NearbyFoodReader2.describeItem(new Tool(), tool);
        assert tool.get("is_food").equals(false);
        assert tool.get("display_category").equals("Cooking");
        assert !tool.containsKey("freshness");
        Container root = new Container(), bag = new Container();
        InventoryReaderTest.Item pack = new InventoryReaderTest.Item(1,"Bag",1);
        pack.bag = bag; root.items.add(pack); bag.items.add(food); bag.explored = false;
        boolean rejected = false;
        try { NearbyFoodReader2.collectNearby(root,"Nearby",new Object(),Object.class,
            Collections.newSetFromMap(new IdentityHashMap<>()),new ArrayList<>(),new ArrayList<>(),0); }
        catch (IllegalStateException expected) { rejected = true; }
        assert rejected && bag.reads == 0 : "Unexplored nested bag was read";
        bag.explored = true; bag.locked = true; rejected = false;
        try { NearbyFoodReader2.collectNearby(root,"Nearby",new Object(),Object.class,
            Collections.newSetFromMap(new IdentityHashMap<>()),new ArrayList<>(),new ArrayList<>(),0); }
        catch (IllegalStateException expected) { rejected = true; }
        assert rejected && bag.reads == 0 : "Locked nested bag was read";
        bag.locked = false;
        List<Map<String,Object>> nested = new ArrayList<>();
        NearbyFoodReader2.collectNearby(root,"Nearby",new Object(),Object.class,
            Collections.newSetFromMap(new IdentityHashMap<>()),nested,new ArrayList<>(),0);
        assert nested.size() == 2 && nested.get(1).get("count").equals(3);
        System.out.println("Food freshness, frozen/cooked flags and cooking-tool classification passed.");
    }
}

