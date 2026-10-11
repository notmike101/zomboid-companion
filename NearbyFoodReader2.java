package pzobserver.v3;
import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Reads existing loot-pane references; never refreshes UI, explores containers or executes Lua. */
public class NearbyFoodReader2 {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java -jar nearby-food-reader.jar PID");
        ProcessHandle process = ProcessHandle.of(Long.parseLong(args[0])).orElseThrow();
        Path expected = Path.of("C:/Games/steam/steamapps/common/ProjectZomboid/ProjectZomboid64.exe");
        if (!Files.isSameFile(Path.of(process.info().command().orElseThrow()), expected))
            throw new IllegalArgumentException("PID is not the selected Project Zomboid installation");
        Files.createDirectories(InventoryReader.ROOT);
        Path output = InventoryReader.ROOT.resolve("nearby-food-" + UUID.randomUUID() + ".json");
        Path jar = Path.of(NearbyFoodReader2.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(jar.toString(), output.toString()); } finally { vm.detach(); }
        String content = Files.readString(output);
        System.out.println(content);
        System.out.println("Snapshot: " + output);
        if (content.startsWith("{\"status\":\"error\"")) throw new IllegalStateException("Snapshot failed; see " + output);
    }

    public static void agentmain(String output, Instrumentation instrumentation) {
        try {
            Path target = Path.of(output).toAbsolutePath().normalize();
            if (!target.getParent().equals(InventoryReader.ROOT.toAbsolutePath().normalize())
                || !target.getFileName().toString().matches("nearby-food-[a-f0-9-]+\\.json"))
                throw new IllegalArgumentException("Invalid observer output path");
            Map<String,Class<?>> loaded = new HashMap<>();
            for (Class<?> c : instrumentation.getAllLoadedClasses())
                if (c.getName().startsWith("zombie.")) loaded.put(c.getName(), c);
            Map<String,Object> result;
            try { result = capture(loaded); }
            catch (Exception e) {
                result = new LinkedHashMap<>();
                result.put("status", "error"); result.put("captured_at", Instant.now().toString());
                result.put("error", e.toString());
            }
            Files.writeString(target, InventoryReader.json(result), StandardOpenOption.CREATE_NEW);
        } catch (Exception e) { System.err.println("Nearby food observer: " + e); }
    }

    static Object get(Object object, String method) throws Exception { return InventoryReader.call(object, method); }
    static Object raw(Object table, Object key) throws Exception {
        if (table == null) throw new IllegalStateException("Missing loot-pane Lua table");
        return table.getClass().getMethod("rawget", Object.class).invoke(table, key);
    }
    static List<Object> buttons(Object table) throws Exception {
        int length = (int)get(table, "len");
        if (length > 1000) throw new IllegalStateException("Unexpected loot-pane size");
        List<Object> result = new ArrayList<>();
        for (int i=1; i<=length; i++) result.add(raw(table, (double)i));
        return result;
    }
    static Object backpacks(Object env, Object player) throws Exception {
        Object data = raw(raw(env,"ISPlayerData"),((Number)get(player,"getPlayerNum")).doubleValue()+1);
        return raw(raw(data,"lootInventory"),"backpacks");
    }
    static List<Object> containerRefs(List<Object> buttons) throws Exception {
        List<Object> refs = new ArrayList<>();
        for (Object button : buttons) refs.add(raw(button,"inventory"));
        return refs;
    }
    static Map<String,Object> capture(Map<String,Class<?>> loaded) throws Exception {
        if (InventoryReader.required(loaded, "zombie.network.GameClient").getField("client").getBoolean(null)
            || InventoryReader.required(loaded, "zombie.network.GameServer").getField("server").getBoolean(null))
            throw new IllegalStateException("Observer is limited to the authorized single-player game");
        Class<?> playerClass = InventoryReader.required(loaded, "zombie.characters.IsoPlayer");
        Object player = playerClass.getMethod("getInstance").invoke(null);
        if (player == null) throw new IllegalStateException("No active character");
        Object square = get(player, "getCurrentSquare");
        if (square == null) throw new IllegalStateException("Character has no current square");
        long start = System.nanoTime();
        Instant captured = Instant.now();
        Object env = InventoryReader.required(loaded, "zombie.Lua.LuaManager").getField("env").get(null);
        Object backpackTable = backpacks(env, player);
        List<Object> before = buttons(backpackTable);
        List<Object> beforeRefs = containerRefs(before);
        List<Map<String,Object>> containers = new ArrayList<>();
        Set<Object> seenContainers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object button : before) {
            Object container = raw(button, "inventory");
            if (container == null || !seenContainers.add(container)) continue;
            String type = String.valueOf(get(container, "getType"));
            if (type.equals("floor")) continue;
            Map<String,Object> record = new LinkedHashMap<>();
            record.put("label", raw(button, "name")); record.put("type", type);
            try {
                if (container == get(player, "getInventory")) throw new IllegalStateException("Carried inventory is outside this query");
                Object source = get(container, "getSourceGrid");
                if (source == null) {
                    Object parent = get(container, "getParent");
                    if (parent != null) source = get(parent, "getSquare");
                }
                if (source == null) throw new IllegalStateException("Cannot establish container's current world square");
                record.put("tile", List.of(get(source,"getX"), get(source,"getY"), get(source,"getZ")));
                if (!(boolean)square.getClass().getMethod("canReachTo", source.getClass()).invoke(square, source))
                    throw new IllegalStateException("Loot-pane reference is no longer within reachable nearby tiles");
                List<Map<String,Object>> rows = new ArrayList<>();
                List<InventoryReader.ContainerState> states = new ArrayList<>();
                Set<Object> items = Collections.newSetFromMap(new IdentityHashMap<>());
                collectNearby(container,"Nearby " + raw(button,"name"),player,
                    InventoryReader.required(loaded,"zombie.characters.IsoGameCharacter"),items,rows,states,0);
                Map<Object,Object> byId = new HashMap<>();
                for (Object item : items) if (!(boolean)get(item,"isHidden"))
                    if (byId.put(get(item,"getID"),item) != null) throw new IllegalStateException("Duplicate item identifier");
                for (Map<String,Object> row : rows) describeItem(byId.get(row.get("id")), row);
                for (InventoryReader.ContainerState state : states)
                    if (!Arrays.equals(state.items(), ((List<?>)get(state.container(),"getItems")).toArray()))
                        throw new IllegalStateException("Container changed during capture; request another snapshot");
                record.put("status", "read"); record.put("items", rows);
            } catch (Exception e) { record.put("status", "unreadable"); record.put("reason", e.toString()); }
            containers.add(record);
        }
        Object currentTable = backpacks(env,player);
        List<Object> after = buttons(currentTable);
        if (player != playerClass.getMethod("getInstance").invoke(null) || square != get(player,"getCurrentSquare")
            || currentTable != backpackTable || !before.equals(after) || !beforeRefs.equals(containerRefs(after)))
            throw new IllegalStateException("Character or loot pane changed during capture");
        Object descriptor = get(player,"getDescriptor");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status","ok"); result.put("captured_at",captured.toString());
        result.put("collection_ms",(System.nanoTime()-start)/1_000_000.0);
        result.put("character",get(descriptor,"getForename") + " " + get(descriptor,"getSurname"));
        result.put("source","Live JVM objects already referenced by the active character's loot pane");
        result.put("scope","Existing loot-pane containers only; verified nearby reachability; explored and unlocked only; floor and carried inventory excluded");
        result.put("coverage","Not a room-wide scan: containers absent from the current loot pane are not inspected");
        result.put("containers",containers);
        return result;
    }

    static void describeItem(Object item, Map<String,Object> row) throws Exception {
        FoodDetails.describe(item, row);
    }
    static void collectNearby(Object container, String path, Object player, Class<?> characterBase,
        Set<Object> seen, List<Map<String,Object>> rows, List<InventoryReader.ContainerState> states, int depth) throws Exception {
        if (container == null || depth > 32) throw new IllegalStateException("Invalid or excessively nested container");
        if (!(boolean)get(container,"isExplored")) throw new IllegalStateException(path + ": unexplored; contents not inspected");
        if ((boolean)container.getClass().getMethod("isLockedToCharacter",characterBase).invoke(container,player))
            throw new IllegalStateException(path + ": locked; contents not inspected");
        Object[] items = ((List<?>)get(container,"getItems")).toArray();
        states.add(new InventoryReader.ContainerState(container,items));
        for (Object item : items) {
            if (item == null || !seen.add(item)) throw new IllegalStateException("Null or duplicate/cyclic inventory item");
            if ((boolean)get(item,"isHidden")) continue;
            if (rows.size() >= 10000) throw new IllegalStateException("Inventory exceeds observer's item limit");
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id",get(item,"getID")); row.put("name",get(item,"getName"));
            row.put("type",get(item,"getFullType")); row.put("count",get(item,"getCount"));
            row.put("container",path); row.put("condition",get(item,"getCondition"));
            row.put("condition_max",get(item,"getConditionMax")); rows.add(row);
            if ((boolean)get(item,"IsInventoryContainer"))
                collectNearby(get(item,"getInventory"),path + " / " + row.get("name") + " [" + row.get("id") + "]",
                    player,characterBase,seen,rows,states,depth+1);
        }
    }
}

