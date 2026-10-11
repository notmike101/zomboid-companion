package pzobserver.v3;
import java.util.*;

/** Shared getter-only metadata; no debug age, hidden poison or item-creation helpers. */
public final class FoodDetails {
    private FoodDetails() {}
    static Object get(Object item, String method) throws Exception { return InventoryReader.call(item, method); }
    static Double finiteNumber(Object value) {
        double number = ((Number)value).doubleValue();
        return Double.isFinite(number) ? number : null;
    }
    static void describe(Object item, Map<String,Object> row) throws Exception {
        boolean food = (boolean)get(item,"IsFood");
        row.put("is_food",food);
        row.put("category",get(item,"getCategory"));
        row.put("display_category",get(item,"getDisplayCategory"));
        row.put("portion_fraction_remaining",null);
        row.put("freshness_time_remaining",null);
        if (!food) {
            row.put("portion_basis","not a Food item; food portions and fluid volume not measured");
            return;
        }
        Double current = finiteNumber(get(item,"getHungChange"));
        Double base = finiteNumber(get(item,"getBaseHunger"));
        row.put("hunger_change_remaining",current);
        row.put("base_hunger",base);
        // The normal eating menu uses these getters to translate original quarters
        // into fractions of the remaining amount. This is not a serving counter.
        if (current != null && base != null && base != 0) {
            double fraction = current / base;
            if (Double.isFinite(fraction) && fraction >= 0 && fraction <= 1)
                row.put("portion_fraction_remaining",fraction == 0 ? 0.0 : fraction);
        }
        row.put("portion_basis",row.get("portion_fraction_remaining") == null
            ? "unknown: zero/nonfinite baseline or current/base hunger ratio outside 0..1"
            : "derived current/base hunger; not servings or original package size");
        row.put("freshness",(boolean)get(item,"isRotten") ? "rotten" : (boolean)get(item,"isFresh") ? "fresh" : "stale");
        row.put("freshness_time_basis","unknown: debug-only age/countdown not read");
        row.put("frozen",get(item,"isFrozen"));
        // isFreezing/isThawing query power via a cache-mutating game method.
        // Use stored progress and pure container getters; leave power-dependent
        // directions unknown rather than updating a world object's power cache.
        boolean canFreeze = (boolean)get(item,"canBeFrozen");
        Double progress = finiteNumber(get(item,"getFreezingTime"));
        if (progress != null && (progress < 0 || progress > 100)) progress = null;
        Object outer = get(item,"getOutermostContainer");
        boolean freezer = outer != null && (boolean)get(outer,"isFreezer");
        row.put("freezing_progress_fraction",progress == null ? null : progress / 100);
        Boolean freezing = null, thawing = null;
        if (!canFreeze) { freezing = false; thawing = false; }
        else if (progress != null) {
            if (!freezer || progress >= 100) freezing = false;
            if (progress <= 0) thawing = false;
            else if (!freezer) thawing = true;
        }
        row.put("freezing",freezing); row.put("thawing",thawing);
        row.put("freezing_direction_basis","derived stored progress and outer container; freezer power not queried, null direction means unknown");
        boolean cooked = (boolean)get(item,"isCooked"), burnt = (boolean)get(item,"isBurnt");
        boolean cookable = (boolean)get(item,"isCookable");
        row.put("cookable",cookable);
        row.put("cooked",cooked); row.put("burnt",burnt);
        row.put("cooking_state",burnt ? "burnt" : cooked ? "cooked" : cookable ? "raw" : "not cookable");
        row.put("dangerous_uncooked",!cooked && !burnt && (boolean)get(item,"isbDangerousUncooked"));
    }
}
