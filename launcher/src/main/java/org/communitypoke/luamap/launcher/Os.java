package org.communitypoke.luamap.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Current-OS detection plus vanilla-style {@code rules} evaluation. */
final class Os {

    private Os() {
    }

    static String name() {
        String n = System.getProperty("os.name", "").toLowerCase();
        if (n.contains("win")) {
            return "windows";
        }
        if (n.contains("mac") || n.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        if (a.contains("aarch64") || a.contains("arm")) {
            return "arm64";
        }
        return a.contains("64") ? "x86_64" : "x86";
    }

    /**
     * Vanilla rule semantics: a list of {action, os{...}} objects — last
     * matching rule wins; with no rules the thing is allowed. Rules that gate
     * on "features" (demo user, custom resolution, ...) never apply here.
     */
    static boolean allowed(JsonElement rulesEl) {
        if (rulesEl == null || !rulesEl.isJsonArray()) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement el : rulesEl.getAsJsonArray()) {
            JsonObject rule = el.getAsJsonObject();
            if (rule.has("features")) {
                continue; // feature-gated rules don't apply to a plain launcher
            }
            if (rule.has("os") && !osMatches(rule.getAsJsonObject("os"))) {
                continue;
            }
            allowed = "allow".equals(rule.get("action").getAsString());
        }
        return allowed;
    }

    private static boolean osMatches(JsonObject os) {
        if (os.has("name") && !name().equals(os.get("name").getAsString())) {
            return false;
        }
        if (os.has("arch")) {
            String want = os.get("arch").getAsString();
            if ("x86".equals(want) && !"x86".equals(arch())) {
                return false;
            }
        }
        return true;
    }
}
