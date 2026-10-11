package pzobserver.v2;
import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** One-shot observer. No transformers, game setters, input, Lua evaluation or background workers. */
public class InventoryReader {
    static final Path ROOT = Path.of("D:/zomboid/snapshots");
    record ContainerState(Object container, Object[] items) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("probe", "inventory").contains(args[1]))
            throw new IllegalArgumentException("Usage: java -jar inventory-reader.jar PID probe|inventory");
        ProcessHandle process = ProcessHandle.of(Long.parseLong(args[0])).orElseThrow();
        String command = process.info().command().orElseThrow();
        Path expected = Path.of("C:/Games/steam/steamapps/common/ProjectZomboid/ProjectZomboid64.exe");
        if (!Files.isSameFile(Path.of(command), expected))
            throw new IllegalArgumentException("PID is not the selected Project Zomboid installation");
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try {
            if (args[1].equals("probe")) {
                Properties p = vm.getSystemProperties();
                System.out.println("Target PID=" + args[0] + "; Java=" + p.getProperty("java.version")
                    + "; java.home=" + p.getProperty("java.home"));
                return;
            }
            Files.createDirectories(ROOT);
            Path output = ROOT.resolve("inventory-" + UUID.randomUUID() + ".json");
            Path jar = Path.of(InventoryReader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            vm.loadAgent(jar.toString(), output.toString());
            if (!Files.isRegularFile(output)) throw new IllegalStateException("Agent produced no snapshot");
            String content = Files.readString(output);
            System.out.println(content);
            System.out.println("Snapshot: " + output);
            if (content.startsWith("{\"status\":\"error\"")) throw new IllegalStateException("Snapshot failed; see " + output);
        } finally { vm.detach(); }
    }

    public static void agentmain(String output, Instrumentation instrumentation) {
        // Never propagate an observer exception into the game's attach thread.
        try {
            Path target = Path.of(output).toAbsolutePath().normalize();
            if (!target.getParent().equals(ROOT.toAbsolutePath().normalize())
                || !target.getFileName().toString().matches("inventory-[a-f0-9-]+\\.json"))
                throw new IllegalArgumentException("Invalid observer output path");
            Map<String,Class<?>> loaded = new HashMap<>();
            for (Class<?> c : instrumentation.getAllLoadedClasses())
                if (c.getName().startsWith("zombie.")) loaded.put(c.getName(), c);
            Map<String,Object> result;
            try {
                result = capture(loaded);
            } catch (Exception e) {
                result = new LinkedHashMap<>();
                result.put("status", "error");
                result.put("captured_at", Instant.now().toString());
                result.put("error", e.toString());
            }
            Files.writeString(target, json(result), StandardOpenOption.CREATE_NEW);
        } catch (Exception e) { System.err.println("Inventory observer: " + e); }
    }

    static Class<?> required(Map<String,Class<?>> loaded, String name) {
        Class<?> c = loaded.get(name);
        if (c == null) throw new IllegalStateException("Game class not loaded: " + name);
        return c;
    }

    static Map<String,Object> capture(Map<String,Class<?>> loaded) throws Exception {
        if (required(loaded, "zombie.network.GameClient").getField("client").getBoolean(null)
            || required(loaded, "zombie.network.GameServer").getField("server").getBoolean(null))
            throw new IllegalStateException("Observer is limited to the authorized single-player game");
        Class<?> playerClass = required(loaded, "zombie.characters.IsoPlayer");
        Object player = playerClass.getMethod("getInstance").invoke(null);
        if (player == null) throw new IllegalStateException("No active character");
        long start = System.nanoTime();
        Instant capturedAt = Instant.now();
        IdentityHashMap<Object,List<String>> equipment = equipment(player);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Map<String,Object>> rows = new ArrayList<>();
        List<ContainerState> containers = new ArrayList<>();
        collect(call(player, "getInventory"), "Inventory", equipment, seen, rows, containers, 0);
        for (Object item : equipment.keySet()) {
            if (!seen.contains(item)) {
                addItem(item, "Equipped outside root inventory", equipment, seen, rows, containers, 0);
            }
        }
        // shortcut: optimistic structural checks, use a game-thread hook if atomic readings become necessary.
        for (ContainerState state : containers) {
            if (!Arrays.equals(state.items(), ((List<?>)call(state.container(), "getItems")).toArray()))
                throw new IllegalStateException("Inventory changed during capture; request another snapshot");
        }
        if (player != playerClass.getMethod("getInstance").invoke(null) || !sameEquipment(equipment, equipment(player)))
            throw new IllegalStateException("Character or equipment changed during capture; request another snapshot");
        Object descriptor = call(player, "getDescriptor");
        Object core = required(loaded, "zombie.core.Core").getMethod("getInstance").invoke(null);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("source", "live JVM character inventory getters");
        result.put("captured_at", capturedAt.toString());
        result.put("collection_ms", (System.nanoTime() - start) / 1_000_000.0);
        result.put("game_version", call(core, "getVersion"));
        result.put("character", call(descriptor, "getForename") + " " + call(descriptor, "getSurname"));
        result.put("item_records", rows.size());
        result.put("hidden_objects_excluded", seen.size() - rows.size());
        result.put("consistency", "Container membership and equipment checked before/after; item values may change while playing");
        result.put("items", rows);
        return result;
    }

    static Object call(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }

    static IdentityHashMap<Object,List<String>> equipment(Object player) throws Exception {
        IdentityHashMap<Object,List<String>> result = new IdentityHashMap<>();
        addEquipment(result, call(player, "getPrimaryHandItem"), "primary hand");
        addEquipment(result, call(player, "getSecondaryHandItem"), "secondary hand");
        for (String kind : List.of("Worn", "Attached")) {
            Object list = call(player, "get" + kind + "Items");
            int size = (int)call(list, "size");
            for (int i=0; i<size; i++) {
                Object entry = list.getClass().getMethod("get", int.class).invoke(list, i);
                addEquipment(result, call(entry, "getItem"), kind.toLowerCase(Locale.ROOT) + ":" + call(entry, "getLocation"));
            }
        }
        return result;
    }

    static void addEquipment(IdentityHashMap<Object,List<String>> equipment, Object item, String slot) {
        if (item != null) equipment.computeIfAbsent(item, ignored -> new ArrayList<>()).add(slot);
    }

    static boolean sameEquipment(IdentityHashMap<Object,List<String>> first, IdentityHashMap<Object,List<String>> second) {
        if (first.size() != second.size()) return false;
        for (Object item : first.keySet())
            if (!first.get(item).equals(second.get(item))) return false;
        return true;
    }

    static void collect(Object container, String path, IdentityHashMap<Object,List<String>> equipment,
                        Set<Object> seen, List<Map<String,Object>> rows, List<ContainerState> states, int depth) throws Exception {
        if (container == null || depth > 32) throw new IllegalStateException("Invalid or excessively nested inventory");
        Object[] items = ((List<?>)call(container, "getItems")).toArray();
        states.add(new ContainerState(container, items));
        for (Object item : items) addItem(item, path, equipment, seen, rows, states, depth);
    }

    static void addItem(Object item, String path, IdentityHashMap<Object,List<String>> equipment,
                        Set<Object> seen, List<Map<String,Object>> rows, List<ContainerState> states, int depth) throws Exception {
        if (item == null || !seen.add(item)) throw new IllegalStateException("Null or duplicate/cyclic inventory item");
        if ((boolean)call(item, "isHidden")) return;
        if (rows.size() >= 10000) throw new IllegalStateException("Inventory exceeds observer's item limit");
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", call(item, "getID"));
        row.put("name", call(item, "getName"));
        row.put("type", call(item, "getFullType"));
        row.put("count", call(item, "getCount"));
        row.put("container", path);
        row.put("equipment", equipment.getOrDefault(item, List.of()));
        row.put("condition", call(item, "getCondition"));
        row.put("condition_max", call(item, "getConditionMax"));
        rows.add(row);
        if ((boolean)call(item, "IsInventoryContainer"))
            collect(call(item, "getInventory"), path + " / " + row.get("name") + " [" + row.get("id") + "]",
                equipment, seen, rows, states, depth+1);
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> map) {
            List<String> fields = new ArrayList<>();
            map.forEach((key, item) -> fields.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Collection<?> list) {
            List<String> elements = new ArrayList<>();
            for (Object item : list) elements.add(json(item));
            return "[" + String.join(",", elements) + "]";
        }
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                default -> { if (c < 32) result.append(String.format("\\u%04x", (int)c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
}
